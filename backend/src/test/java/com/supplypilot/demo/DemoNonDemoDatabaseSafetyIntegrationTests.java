package com.supplypilot.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import com.supplypilot.SupplyPilotApplication;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.env.*;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/** A caller cannot designate a shared database as demo merely by matching its allowed name. */
class DemoNonDemoDatabaseSafetyIntegrationTests {
    private static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4.11"))
            .withDatabaseName("shared_documents").withCommand("--log-bin-trust-function-creators=1");
    private static Path storage; private static Path sentinel;
    @BeforeAll static void setup() throws Exception { MYSQL.start(); storage=Files.createTempDirectory("demo-documents-shared-"); sentinel=Files.writeString(storage.resolve("keep.txt"),"must-not-change"); sql("CREATE TABLE sentinel_records (id INT PRIMARY KEY, note VARCHAR(32))"); sql("INSERT INTO sentinel_records VALUES (1, 'must-not-change')"); }
    @AfterAll static void cleanup() throws Exception { MYSQL.stop(); if(storage!=null)try(var paths=Files.walk(storage)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception e){throw new IllegalStateException(e);}});} }
    @Test void refusesSharedDatabaseBeforeFlywayEvenWhenAllowedNameMatches() throws Exception { Snapshot before=snapshot(); assertThatThrownBy(DemoNonDemoDatabaseSafetyIntegrationTests::start).hasRootCauseInstanceOf(IllegalStateException.class).hasRootCauseMessage("The allowed MySQL database is not explicitly demo-isolated"); assertThat(snapshot()).isEqualTo(before); assertThat(sentinel).hasContent("must-not-change"); try(var files=Files.list(storage)){assertThat(files).containsExactly(sentinel);} }
    private static Snapshot snapshot() throws Exception { return new Snapshot(query("SELECT COUNT(*) FROM sentinel_records"), tables(), Files.list(storage).map(p->p.getFileName().toString()).sorted().toList()); }
    private static List<String> tables() throws Exception { try(var c=DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());var s=c.createStatement();var r=s.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() ORDER BY table_name")){var v=new ArrayList<String>();while(r.next())v.add(r.getString(1));return v;} }
    private static long query(String q)throws Exception{try(var c=DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());var s=c.createStatement();var r=s.executeQuery(q)){r.next();return r.getLong(1);}}
    private static void sql(String q)throws Exception{try(var c=DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());var s=c.createStatement()){s.execute(q);}}
    private static void start(){Map<String,Object> p=Map.of("DB_URL",MYSQL.getJdbcUrl(),"DB_USERNAME",MYSQL.getUsername(),"DB_PASSWORD",MYSQL.getPassword(),"supplypilot.demo.allowed-database","shared_documents","supplypilot.document-storage.path",storage.toString(),"supplypilot.demo.account-password","Demo-Password-For-Tests-901!","supplypilot.jwt.secret","dGVzdC1zZWNyZXQtZm9yLXNoYXJlZC1kYi0yNTYtYml0cw=="); StandardEnvironment e=new StandardEnvironment();e.getPropertySources().addFirst(new MapPropertySource("isolatedSharedDatabase",p));new SpringApplicationBuilder(SupplyPilotApplication.class).profiles("demo").web(WebApplicationType.NONE).environment(e).initializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolatedSharedDatabaseInitializer",p))).properties("supplypilot.demo-data-enabled=false","spring.flyway.enabled=true").run();}
    private record Snapshot(long rows,List<String> tables,List<String> files){}
}

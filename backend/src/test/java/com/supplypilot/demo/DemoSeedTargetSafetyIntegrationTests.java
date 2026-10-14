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

/** Real startup failures prove unsafe targets cannot reach Flyway. */
class DemoSeedTargetSafetyIntegrationTests {
    private static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4.11"))
            .withDatabaseName("supplypilot_demo_target_safety_test").withCommand("--log-bin-trust-function-creators=1");
    private static Path root;
    @BeforeAll static void setup() throws Exception { MYSQL.start(); root=Files.createTempDirectory("supplypilot-target-safety-"); sql("CREATE TABLE sentinel_records (id INT PRIMARY KEY, note VARCHAR(32))"); sql("INSERT INTO sentinel_records VALUES (1, 'must-not-change')"); }
    @AfterAll static void cleanup() throws Exception { MYSQL.stop(); if(root!=null) try(var paths=Files.walk(root)){ paths.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.deleteIfExists(path);}catch(Exception e){throw new IllegalStateException(e);}}); } }
    @Test void wrongMysqlTargetFailsBeforeFlywayAndLeavesSentinelsUntouched() throws Exception { Path storage=Files.createDirectories(root.resolve("demo-documents-wrong-db")); Path file=Files.writeString(storage.resolve("keep.txt"),"must-not-change"); Snapshot before=snapshot(storage); assertThatThrownBy(()->start("different_demo_database",storage)).hasRootCauseInstanceOf(IllegalStateException.class).hasRootCauseMessage("Refusing demo initialization for unexpected MySQL database: "+MYSQL.getDatabaseName()); assertUnchanged(before,storage,file); }
    @Test void nonDemoStorageRootFailsBeforeFlywayEvenWhenParentContainsDemo() throws Exception { Path storage=Files.createDirectories(root.resolve("demo-project/data/documents")); Path file=Files.writeString(storage.resolve("keep.txt"),"must-not-change"); Snapshot before=snapshot(storage); assertThatThrownBy(()->start(MYSQL.getDatabaseName(),storage)).hasRootCauseInstanceOf(IllegalStateException.class).hasRootCauseMessage("Refusing demo initialization for a document storage path not identified as demo"); assertUnchanged(before,storage,file); }
    private static void assertUnchanged(Snapshot before,Path storage,Path file)throws Exception{assertThat(snapshot(storage)).isEqualTo(before);assertThat(file).hasContent("must-not-change");try(var paths=Files.list(storage)){assertThat(paths).containsExactly(file);}}
    private static Snapshot snapshot(Path storage)throws Exception{return new Snapshot(query("SELECT COUNT(*) FROM sentinel_records"),tables(),Files.list(storage).map(path->path.getFileName().toString()).sorted().toList());}
    private static List<String> tables()throws Exception{try(var c=DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());var s=c.createStatement();var r=s.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() ORDER BY table_name")){var names=new ArrayList<String>();while(r.next())names.add(r.getString(1));return names;}}
    private static long query(String sql)throws Exception{try(var c=DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());var s=c.createStatement();var r=s.executeQuery(sql)){r.next();return r.getLong(1);}}
    private static void sql(String sql)throws Exception{try(var c=DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());var s=c.createStatement()){s.execute(sql);}}
    private static void start(String allowed,Path storage){Map<String,Object> p=Map.of("DB_URL",MYSQL.getJdbcUrl(),"DB_USERNAME",MYSQL.getUsername(),"DB_PASSWORD",MYSQL.getPassword(),"supplypilot.demo.allowed-database",allowed,"supplypilot.document-storage.path",storage.toString(),"supplypilot.demo.account-password","Demo-Password-For-Tests-901!","supplypilot.jwt.secret","dGVzdC1zZWNyZXQtZm9yLXRhcmdldC1zYWZldHktMjU2LWJpdHM=");StandardEnvironment e=new StandardEnvironment();e.getPropertySources().addFirst(new MapPropertySource("isolatedTargetSafety",p));new SpringApplicationBuilder(SupplyPilotApplication.class).profiles("demo").web(WebApplicationType.NONE).environment(e).initializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolatedTargetSafetyInitializer",p))).properties("supplypilot.demo-data-enabled=false","spring.flyway.enabled=true").run();}
    private record Snapshot(long rows,List<String> tables,List<String> files){}
}

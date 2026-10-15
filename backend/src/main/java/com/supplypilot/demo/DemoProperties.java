package com.supplypilot.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "supplypilot.demo")
public class DemoProperties {
    private String accountPassword;
    private String baselineDate;
    private String datasetVersion = "sp-905-zh-tw-v1";
    private String samplesPath = "../samples/documents";
    private String resetConfirmation;
    private String allowedDatabase = "supplypilot_demo";
    private String allowedSemanticDatabase = "supplypilot_semantic_demo";

    public String getAccountPassword() { return accountPassword; }
    public void setAccountPassword(String accountPassword) { this.accountPassword = accountPassword; }
    public String getBaselineDate() { return baselineDate; }
    public void setBaselineDate(String baselineDate) { this.baselineDate = baselineDate; }
    public String getDatasetVersion() { return datasetVersion; }
    public void setDatasetVersion(String datasetVersion) { this.datasetVersion = datasetVersion; }
    public String getSamplesPath() { return samplesPath; }
    public void setSamplesPath(String samplesPath) { this.samplesPath = samplesPath; }
    public String getResetConfirmation() { return resetConfirmation; }
    public void setResetConfirmation(String resetConfirmation) { this.resetConfirmation = resetConfirmation; }
    public String getAllowedDatabase() { return allowedDatabase; }
    public void setAllowedDatabase(String allowedDatabase) { this.allowedDatabase = allowedDatabase; }
    public String getAllowedSemanticDatabase() { return allowedSemanticDatabase; }
    public void setAllowedSemanticDatabase(String allowedSemanticDatabase) {
        this.allowedSemanticDatabase = allowedSemanticDatabase;
    }
}

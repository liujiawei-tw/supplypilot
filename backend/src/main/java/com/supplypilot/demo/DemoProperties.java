package com.supplypilot.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "supplypilot.demo")
public class DemoProperties {
    private String baselineDate;
    private String datasetVersion = "sp-907-zh-tw-v3";
    private String masterDataPath = "../samples/demo-master-data.json";
    private String samplesPath = "../samples/documents";
    private String resetConfirmation;
    private String allowedDatabase = "supplypilot_demo";
    private String upgradeMode = "AUTO_SAFE";
    private String upgradeConfirmation;
    private String upgradeApprovedPlanSha256;
    private String allowedSemanticDatabase = "supplypilot_semantic_demo";

    public String getBaselineDate() { return baselineDate; }
    public void setBaselineDate(String baselineDate) { this.baselineDate = baselineDate; }
    public String getDatasetVersion() { return datasetVersion; }
    public void setDatasetVersion(String datasetVersion) { this.datasetVersion = datasetVersion; }
    public String getMasterDataPath() { return masterDataPath; }
    public void setMasterDataPath(String masterDataPath) { this.masterDataPath = masterDataPath; }
    public String getSamplesPath() { return samplesPath; }
    public void setSamplesPath(String samplesPath) { this.samplesPath = samplesPath; }
    public String getResetConfirmation() { return resetConfirmation; }
    public void setResetConfirmation(String resetConfirmation) { this.resetConfirmation = resetConfirmation; }
    public String getUpgradeMode() { return upgradeMode; }
    public void setUpgradeMode(String upgradeMode) { this.upgradeMode = upgradeMode; }
    public String getUpgradeConfirmation() { return upgradeConfirmation; }
    public void setUpgradeConfirmation(String upgradeConfirmation) { this.upgradeConfirmation = upgradeConfirmation; }
    public String getUpgradeApprovedPlanSha256() { return upgradeApprovedPlanSha256; }
    public void setUpgradeApprovedPlanSha256(String upgradeApprovedPlanSha256) {
        this.upgradeApprovedPlanSha256 = upgradeApprovedPlanSha256;
    }
    public String getAllowedDatabase() { return allowedDatabase; }
    public void setAllowedDatabase(String allowedDatabase) { this.allowedDatabase = allowedDatabase; }
    public String getAllowedSemanticDatabase() { return allowedSemanticDatabase; }
    public void setAllowedSemanticDatabase(String allowedSemanticDatabase) {
        this.allowedSemanticDatabase = allowedSemanticDatabase;
    }
}

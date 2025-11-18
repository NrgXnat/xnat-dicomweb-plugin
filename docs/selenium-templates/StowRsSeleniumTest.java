/*
 * XNAT DICOMweb Proxy Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */

package org.nrg.xnat.dicomweb.selenium;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * Selenium integration test for STOW-RS functionality
 *
 * NOTE: This test is OPTIONAL and requires additional setup:
 * - Add Selenium dependencies to build.gradle:
 *   testImplementation 'org.seleniumhq.selenium:selenium-java:4.15.0'
 *   testImplementation 'org.seleniumhq.selenium:selenium-chrome-driver:4.15.0'
 * - ChromeDriver installed and in PATH
 * - XNAT instance running at http://localhost
 * - Test project "test" exists
 * - Admin credentials: admin/admin
 *
 * By default, this test is SKIPPED via SKIP_SELENIUM_TESTS=true assumption.
 *
 * To run:
 * SKIP_SELENIUM_TESTS=false ./gradlew test --tests StowRsSeleniumTest
 *
 * For automated testing, use test/test_stow_rs.sh instead.
 */
public class StowRsSeleniumTest {

    private static final String XNAT_URL = System.getProperty("xnat.url", "http://localhost");
    private static final String ADMIN_USER = System.getProperty("xnat.user", "admin");
    private static final String ADMIN_PASS = System.getProperty("xnat.password", "admin");
    private static final String TEST_PROJECT = System.getProperty("xnat.project", "test");

    private WebDriver driver;
    private WebDriverWait wait;
    private File tempDicomFile;

    @Before
    public void setUp() throws IOException {
        // Skip if Selenium tests are disabled
        assumeTrue("Selenium tests disabled via SKIP_SELENIUM_TESTS",
            !"true".equals(System.getenv("SKIP_SELENIUM_TESTS")));

        // Skip if ChromeDriver not available
        try {
            ChromeOptions options = new ChromeOptions();
            options.addArguments("--headless");
            options.addArguments("--no-sandbox");
            options.addArguments("--disable-dev-shm-usage");
            options.addArguments("--disable-gpu");

            driver = new ChromeDriver(options);
            driver.manage().timeouts().implicitlyWait(10, TimeUnit.SECONDS);
            wait = new WebDriverWait(driver, 20);
        } catch (Exception e) {
            assumeTrue("ChromeDriver not available: " + e.getMessage(), false);
        }

        // Create minimal test DICOM file
        tempDicomFile = createTestDicomFile();
    }

    @After
    public void tearDown() {
        if (driver != null) {
            driver.quit();
        }
        if (tempDicomFile != null && tempDicomFile.exists()) {
            tempDicomFile.delete();
        }
    }

    @Test
    public void testStowRsUploadViaXnatUI() {
        // Login to XNAT
        loginToXnat();

        // Navigate to project
        driver.get(XNAT_URL + "/app/action/DisplayItemAction/search_element/xnat:projectData/search_field/xnat:projectData.ID/search_value/" + TEST_PROJECT);

        // Wait for project page to load
        wait.until(ExpectedConditions.presenceOfElementLocated(By.id("project-details")));

        // Note: This test verifies the STOW-RS endpoint is accessible
        // Actual file upload via Selenium would require custom UI or REST client

        // Verify STOW-RS endpoint is accessible (would return 401 unauthorized without auth)
        String stowUrl = XNAT_URL + "/xapi/dicomweb/projects/" + TEST_PROJECT + "/studies";

        // Open new tab to test endpoint
        driver.executeScript("window.open('" + stowUrl + "','_blank');");

        // Switch to new tab
        String[] handles = driver.getWindowHandles().toArray(new String[0]);
        driver.switchTo().window(handles[handles.length - 1]);

        // Should get some response (not 404)
        String pageSource = driver.getPageSource();
        assertFalse("STOW-RS endpoint should not return 404", pageSource.contains("404 – Not Found"));
    }

    @Test
    public void testStowRsEndpointExists() {
        // Simple test to verify endpoint responds
        loginToXnat();

        // Use JavaScript to test endpoint via fetch
        String script =
            "return fetch('" + XNAT_URL + "/xapi/dicomweb/projects/" + TEST_PROJECT + "/studies', {" +
            "  method: 'POST'," +
            "  headers: { 'Content-Type': 'multipart/related; boundary=test' }," +
            "  body: '--test--'" +
            "})" +
            ".then(r => r.status)" +
            ".catch(e => 0);";

        // Execute and get status code
        Object result = driver.executeAsyncScript(
            "var callback = arguments[arguments.length - 1];" +
            script.replace("return", "").replace(";", ".then(callback).catch(() => callback(0));")
        );

        // Should not be 404 (endpoint exists)
        // May be 400 (bad request), 401 (unauthorized), or 415 (unsupported media type)
        // but not 404
        assertNotNull("Fetch should return a status code", result);
        long status = ((Number) result).longValue();
        assertNotEquals("STOW-RS endpoint should exist (not 404)", 404L, status);
    }

    /**
     * Login to XNAT
     */
    private void loginToXnat() {
        driver.get(XNAT_URL + "/app/template/Login.vm");

        WebElement username = wait.until(ExpectedConditions.presenceOfElementLocated(By.name("username")));
        WebElement password = driver.findElement(By.name("password"));
        WebElement loginButton = driver.findElement(By.name("login"));

        username.sendKeys(ADMIN_USER);
        password.sendKeys(ADMIN_PASS);
        loginButton.click();

        // Wait for login to complete
        wait.until(ExpectedConditions.urlContains("/app/template/Index.vm"));
    }

    /**
     * Create a minimal test DICOM file
     */
    private File createTestDicomFile() throws IOException {
        File tempFile = File.createTempFile("test_dicom_", ".dcm");

        // Create minimal DICOM file with preamble and DICM marker
        byte[] preamble = new byte[128]; // 128 zero bytes
        byte[] dicm = new byte[] { 'D', 'I', 'C', 'M' };

        // Write minimal DICOM file
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            fos.write(preamble);
            fos.write(dicm);
            // Add minimal required tags (would need dcm4che for real DICOM)
            // For now, just preamble + DICM is enough for testing structure
        }

        return tempFile;
    }
}

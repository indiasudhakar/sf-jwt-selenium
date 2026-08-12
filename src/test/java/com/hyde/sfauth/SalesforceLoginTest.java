package com.hyde.sfauth;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.time.Duration;

/**
 * End-to-end check: authenticate via JWT (no passkey), open Salesforce already
 * logged in, confirm we landed on an authenticated page (not the login screen),
 * and hold the browser open so you can eyeball the home screen.
 *
 * Run with:  mvn test
 */
public class SalesforceLoginTest {

    private WebDriver driver;

    @BeforeClass
    public void setUp() throws Exception {
        // 1. Auth + frontdoor URL — the whole passkey-free login happens here.
        JWTAuthManager auth = new JWTAuthManager();
        String loginUrl = auth.buildLoginUrl(SalesforceConfig.START_PATH);

        // 2. Launch Chrome (Selenium 4 auto-manages the driver binary).
        ChromeOptions options = new ChromeOptions();
        if (SalesforceConfig.HEADLESS) {
            options.addArguments("--headless=new");
        }
        options.addArguments("--window-size=1440,900");
        options.addArguments("--remote-allow-origins=*");
        driver = new ChromeDriver(options);

        // 3. Load the frontdoor URL — the browser comes back authenticated.
        driver.get(loginUrl);

        // 4. Wait until we've left frontdoor and are not sitting on a login page.
        new WebDriverWait(driver, Duration.ofSeconds(30)).until(d ->
                !d.getCurrentUrl().contains("frontdoor.jsp")
                        && !d.getCurrentUrl().toLowerCase().contains("/login")
                        && "complete".equals(((JavascriptExecutor) d)
                            .executeScript("return document.readyState")));
    }

    @Test
    public void showsAuthenticatedHomeScreen() throws InterruptedException {
        String url = driver.getCurrentUrl();
        System.out.println("Landed on : " + url);
        System.out.println("Page title: " + driver.getTitle());

        // If auth failed we'd be bounced to the login screen.
        Assert.assertFalse(url.toLowerCase().contains("/login"),
                "Still on the login screen — check the web (or full) OAuth scope on the Connected App.");

        // Keep the browser open so you can visually confirm the home screen.
        int wait = SalesforceConfig.CONFIRM_WAIT_SECONDS;
        System.out.println("Home screen loaded. Holding browser open for " + wait + "s...");
        Thread.sleep(wait * 1000L);
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() {
        if (driver != null) {
            driver.quit();
        }
    }
}

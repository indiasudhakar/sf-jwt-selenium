package com.hyde.sfauth;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.openqa.selenium.virtualauthenticator.HasVirtualAuthenticator;
import org.openqa.selenium.virtualauthenticator.VirtualAuthenticatorOptions;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.List;

/**
 * End-to-end check: authenticate via JWT, open Salesforce already logged in,
 * confirm we landed on an authenticated page (not the login/passkey screen),
 * and hold the browser open so you can eyeball the home screen.
 *
 * The automation user currently has no WebAuthn verification method enrolled,
 * so the first authenticated session Salesforce hands back is the "Create a
 * Passkey" enrollment screen (/_ui/identity/webauthn/AddPasskeyUi), not home.
 * A Selenium virtual authenticator (CDP-level, Chrome/Edge only) registers a
 * software credential that Chrome auto-supplies for that WebAuthn ceremony —
 * no human, no physical key — so the enrollment completes and Salesforce
 * proceeds to retURL. This is the standards-based way Selenium/Chrome support
 * testing WebAuthn-gated flows; it is not a substitute for getting Salesforce
 * Support to grant this user a proper MFA exemption long-term.
 *
 * Run with:  mvn test
 */
public class SalesforceLoginTest {

    private WebDriver driver;

    @BeforeClass
    public void setUp() throws Exception {
        // 1. Auth + frontdoor URL.
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

        // 2b. Register a virtual WebAuthn authenticator so any passkey
        // create()/get() ceremony Salesforce triggers is satisfied silently.
        VirtualAuthenticatorOptions vaOptions = new VirtualAuthenticatorOptions()
                .setProtocol(VirtualAuthenticatorOptions.Protocol.CTAP2)
                .setTransport(VirtualAuthenticatorOptions.Transport.INTERNAL)
                .setHasResidentKey(true)
                .setHasUserVerification(true)
                .setIsUserVerified(true);
        ((HasVirtualAuthenticator) driver).addVirtualAuthenticator(vaOptions);

        // 3. Load the frontdoor URL — the browser comes back authenticated.
        driver.get(loginUrl);

        // 4. If Salesforce nudges an unenrolled user to create a passkey,
        // click through it — the virtual authenticator answers the WebAuthn
        // call transparently, no dialog ever appears.
        if (driver.getCurrentUrl().toLowerCase().contains("webauthn")) {
            clickPasskeyCreateButton();
        }

        // 5. Wait until we've left frontdoor/webauthn/login pages entirely.
        new WebDriverWait(driver, Duration.ofSeconds(30)).until(d ->
                !d.getCurrentUrl().contains("frontdoor.jsp")
                        && !d.getCurrentUrl().toLowerCase().contains("webauthn")
                        && !d.getCurrentUrl().toLowerCase().contains("/login")
                        && "complete".equals(((JavascriptExecutor) d)
                            .executeScript("return document.readyState")));
    }

    /**
     * The exact button text/markup on AddPasskeyUi isn't known ahead of time,
     * so this tries a few common labels. Run with SF_HEADLESS=false (default)
     * once to watch it and adjust the selector if none of these match.
     */
    private void clickPasskeyCreateButton() {
        List<String> labels = List.of("Create Passkey", "Continue", "Create a Passkey", "Next");
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));
        for (String label : labels) {
            try {
                WebElement btn = wait.until(ExpectedConditions.elementToBeClickable(
                        By.xpath("//button[contains(., '" + label + "')] | //a[contains(., '" + label + "')]")));
                btn.click();
                return;
            } catch (Exception ignored) {
                // try next label
            }
        }
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

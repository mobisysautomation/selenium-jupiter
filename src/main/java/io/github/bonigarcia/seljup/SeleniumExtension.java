/*
 * (C) Copyright 2017 Boni Garcia (http://bonigarcia.github.io/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package io.github.bonigarcia.seljup;

import static com.google.common.collect.ImmutableList.copyOf;
import static java.lang.invoke.MethodHandles.lookup;
import static java.nio.charset.Charset.defaultCharset;
import static java.nio.file.Files.readAllBytes;
import static java.nio.file.Paths.get;
import static java.util.Arrays.asList;
import static java.util.Arrays.stream;
import static java.util.Collections.singletonList;
import static java.util.Optional.empty;
import static java.util.stream.Collectors.toList;
import static org.slf4j.LoggerFactory.getLogger;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestTemplateInvocationContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.ie.InternetExplorerDriver;
import org.openqa.selenium.opera.OperaDriver;
import org.openqa.selenium.phantomjs.PhantomJSDriver;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.safari.SafariDriver;
import org.slf4j.Logger;

import com.google.gson.Gson;

import io.appium.java_client.AppiumDriver;
import io.github.bonigarcia.seljup.BrowsersTemplate.Browser;
import io.github.bonigarcia.seljup.config.Config;
import io.github.bonigarcia.seljup.handler.AppiumDriverHandler;
import io.github.bonigarcia.seljup.handler.ChromeDriverHandler;
import io.github.bonigarcia.seljup.handler.DriverHandler;
import io.github.bonigarcia.seljup.handler.EdgeDriverHandler;
import io.github.bonigarcia.seljup.handler.FirefoxDriverHandler;
import io.github.bonigarcia.seljup.handler.ListDriverHandler;
import io.github.bonigarcia.seljup.handler.OperaDriverHandler;
import io.github.bonigarcia.seljup.handler.OtherDriverHandler;
import io.github.bonigarcia.seljup.handler.RemoteDriverHandler;
import io.github.bonigarcia.seljup.handler.SafariDriverHandler;
import io.github.bonigarcia.wdm.WebDriverManager;

/**
 * Selenium extension for Jupiter (JUnit 5) tests.
 *
 * @author Boni Garcia (boni.gg@gmail.com)
 * @since 1.0.0
 */
public class SeleniumExtension implements ParameterResolver, AfterEachCallback,
        TestTemplateInvocationContextProvider {

    final Logger log = getLogger(lookup().lookupClass());

    static final String CLASSPATH_PREFIX = "classpath:";

    private Config config = new Config();
    private InternalPreferences preferences = new InternalPreferences(
            getConfig());
    private List<Class<?>> typeList = new CopyOnWriteArrayList<>();
    private Map<String, List<DriverHandler>> driverHandlerMap = new ConcurrentHashMap<>();
    private Map<String, Class<?>> handlerMap = new ConcurrentHashMap<>();
    private Map<String, Class<?>> templateHandlerMap = new ConcurrentHashMap<>();
    private Map<String, Map<String, DockerContainer>> containersMap = new ConcurrentHashMap<>();
    private DockerService dockerService;
    private Map<String, List<Browser>> browserListMap = new ConcurrentHashMap<>();
    private List<List<Browser>> browserListList;

    public SeleniumExtension() {
        addEntry(handlerMap, "org.openqa.selenium.chrome.ChromeDriver",
                ChromeDriverHandler.class);
        addEntry(handlerMap, "org.openqa.selenium.firefox.FirefoxDriver",
                FirefoxDriverHandler.class);
        addEntry(handlerMap, "org.openqa.selenium.edge.EdgeDriver",
                EdgeDriverHandler.class);
        addEntry(handlerMap, "org.openqa.selenium.opera.OperaDriver",
                OperaDriverHandler.class);
        addEntry(handlerMap, "org.openqa.selenium.safari.SafariDriver",
                SafariDriverHandler.class);
        addEntry(handlerMap, "org.openqa.selenium.remote.RemoteWebDriver",
                RemoteDriverHandler.class);
        addEntry(handlerMap, "org.openqa.selenium.WebDriver",
                RemoteDriverHandler.class);
        addEntry(handlerMap, "io.appium.java_client.AppiumDriver",
                AppiumDriverHandler.class);
        addEntry(handlerMap, "java.util.List", ListDriverHandler.class);
        addEntry(handlerMap, "org.openqa.selenium.phantomjs.PhantomJSDriver",
                OtherDriverHandler.class);

        addEntry(templateHandlerMap, "chrome", ChromeDriver.class);
        addEntry(templateHandlerMap, "firefox", FirefoxDriver.class);
        addEntry(templateHandlerMap, "edge", EdgeDriver.class);
        addEntry(templateHandlerMap, "opera", OperaDriver.class);
        addEntry(templateHandlerMap, "safari", SafariDriver.class);
        addEntry(templateHandlerMap, "appium", AppiumDriver.class);
        addEntry(templateHandlerMap, "phantomjs", PhantomJSDriver.class);
        addEntry(templateHandlerMap, "iexplorer", InternetExplorerDriver.class);
        addEntry(templateHandlerMap, "chrome-in-docker", RemoteWebDriver.class);
        addEntry(templateHandlerMap, "firefox-in-docker",
                RemoteWebDriver.class);
        addEntry(templateHandlerMap, "opera-in-docker", RemoteWebDriver.class);
        addEntry(templateHandlerMap, "android", RemoteWebDriver.class);
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext,
            ExtensionContext extensionContext) {
        Class<?> type = parameterContext.getParameter().getType();
        return (WebDriver.class.isAssignableFrom(type)
                || type.equals(List.class))
                && !isTestTemplate(extensionContext);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext,
            ExtensionContext extensionContext) {

        String contextId = extensionContext.getUniqueId();
        Parameter parameter = parameterContext.getParameter();
        Class<?> type = parameter.getType();
        boolean isTemplate = isTestTemplate(extensionContext);
        boolean isGeneric = type.equals(RemoteWebDriver.class)
                || type.equals(WebDriver.class);
        String url = null;
        Browser browser = null;

        // Check template
        if (isGeneric && !browserListMap.isEmpty()) {
            browser = getBrowser(contextId, parameter, isTemplate);
        }
        if (browser != null) {
            type = templateHandlerMap.get(browser.getType());
            url = browser.getUrl();
        }

        // WebDriverManager
        if (!typeList.contains(type)) {
            WebDriverManager.getInstance(type).setup();
            typeList.add(type);
        }

        // Handler
        DriverHandler driverHandler = null;
        Class<?> constructorClass = handlerMap.containsKey(type.getName())
                ? handlerMap.get(type.getName())
                : OtherDriverHandler.class;
        boolean isRemote = constructorClass.equals(RemoteDriverHandler.class);

        if (url != null && !url.isEmpty()) {
            constructorClass = RemoteDriverHandler.class;
            isRemote = true;
        }

        try {
            driverHandler = getDriverHandler(extensionContext, parameter, type,
                    constructorClass, browser, isRemote);

            if (type.equals(RemoteWebDriver.class)
                    || type.equals(WebDriver.class)
                    || type.equals(List.class)) {
                initHandlerForDocker(contextId, driverHandler);
            }

            if (!isTemplate && isGeneric && isRemote) {
                ((RemoteDriverHandler) driverHandler).setParent(this);
                ((RemoteDriverHandler) driverHandler)
                        .setParameterContext(parameterContext);
            }

            putDriverHandlerInMap(extensionContext.getUniqueId(),
                    driverHandler);

        } catch (Exception e) {
            handleException(parameter, driverHandler, constructorClass, e);
        }

        return resolveHandler(parameter, driverHandler);
    }

    private void putDriverHandlerInMap(String contextId,
            DriverHandler driverHandler) {
        String newContextId = searchContextIdKeyInMap(driverHandlerMap,
                contextId);
        log.trace(
                "Put driver handler {} in map (context id {}, new context id {})",
                driverHandler, contextId, newContextId);
        if (driverHandlerMap.containsKey(contextId)) {
            driverHandlerMap.get(contextId).add(driverHandler);
            log.trace("Adding {} to handler existing map (id {})",
                    driverHandler, contextId);
        } else if (driverHandlerMap.containsKey(newContextId)) {
            driverHandlerMap.get(newContextId).add(driverHandler);
            log.trace("Adding {} to handler existing map (new id {})",
                    driverHandler, newContextId);
        } else {
            List<DriverHandler> driverHandlers = new ArrayList<>();
            driverHandlers.add(driverHandler);
            driverHandlerMap.put(contextId, driverHandlers);
            log.trace("Adding {} to handler new map (id {})", driverHandler,
                    contextId);
        }
    }

    private Browser getBrowser(String contextId, Parameter parameter,
            boolean isTemplate) {
        Integer index = isTemplate
                ? Integer.valueOf(parameter.getName().replaceAll("arg", ""))
                : 0;
        Browser browser = null;
        List<Browser> browserList = getValueFromContextId(browserListMap,
                contextId);
        if (browserList == null) {
            log.warn("Browser list for context id {} not found", contextId);
        } else {
            browser = browserList.get(index);
        }
        return browser;
    }

    private Object resolveHandler(Parameter parameter,
            DriverHandler driverHandler) {
        if (driverHandler != null) {
            driverHandler.resolve();
            return driverHandler.getObject();
        } else if (getConfig().isExceptionWhenNoDriver()) {
            throw new SeleniumJupiterException(
                    "No valid handler for " + parameter + " was found");
        } else {
            return null;
        }
    }

    private DriverHandler getDriverHandler(ExtensionContext extensionContext,
            Parameter parameter, Class<?> type, Class<?> constructorClass,
            Browser browser, boolean isRemote)
            throws InstantiationException, IllegalAccessException,
            InvocationTargetException, NoSuchMethodException {
        DriverHandler driverHandler = null;
        if (isRemote && browser != null) {
            driverHandler = (DriverHandler) constructorClass
                    .getDeclaredConstructor(Parameter.class,
                            ExtensionContext.class, Config.class, Browser.class)
                    .newInstance(parameter, extensionContext, getConfig(),
                            browser);

        } else if (constructorClass.equals(OtherDriverHandler.class)
                && !browserListMap.isEmpty()) {
            driverHandler = (DriverHandler) constructorClass
                    .getDeclaredConstructor(Parameter.class,
                            ExtensionContext.class, Config.class, Class.class)
                    .newInstance(parameter, extensionContext, getConfig(),
                            type);

        } else {
            driverHandler = (DriverHandler) constructorClass
                    .getDeclaredConstructor(Parameter.class,
                            ExtensionContext.class, Config.class)
                    .newInstance(parameter, extensionContext, getConfig());

        }
        return driverHandler;
    }

    public void initHandlerForDocker(String contextId,
            DriverHandler driverHandler) {

        LinkedHashMap<String, DockerContainer> containerMap = new LinkedHashMap<>();
        driverHandler.setContainerMap(containerMap);
        log.trace("Adding new container map (context id {}) (handler {})",
                contextId, driverHandler);
        containersMap.put(contextId, containerMap);

        if (dockerService == null) {
            dockerService = new DockerService(config, preferences);
        }
        driverHandler.setDockerService(dockerService);
    }

    private void handleException(Parameter parameter,
            DriverHandler driverHandler, Class<?> constructorClass,
            Exception e) {
        if (driverHandler != null
                && driverHandler.throwExceptionWhenNoDriver()) {
            log.error("Exception resolving {}", parameter, e);
            throw new SeleniumJupiterException(e);
        } else {
            log.warn("Exception creating {}", constructorClass, e);
        }
    }

    @Override
    public void afterEach(ExtensionContext extensionContext) {
        // Make screenshots if required and close browsers
        ScreenshotManager screenshotManager = new ScreenshotManager(
                extensionContext, getConfig());

        String contextId = extensionContext.getUniqueId();
        log.trace("After each for context id {}", contextId);

        List<DriverHandler> driverHandlers = getValueFromContextId(
                driverHandlerMap, contextId);
        if (driverHandlers == null) {
            log.warn("Driver handler for context id {} not found", contextId);
            return;
        }

        for (DriverHandler driverHandler : copyOf(driverHandlers).reverse()) {
            // Quit WebDriver object
            Object object = driverHandler.getObject();
            try {
                quitWebDriver(object, driverHandler, screenshotManager);
            } catch (Exception e) {
                log.warn("Exception closing webdriver object {}", object, e);
            }

            // Clean handler
            try {
                driverHandler.cleanup();
            } catch (Exception e) {
                log.warn("Exception cleaning handler {}", driverHandler, e);
            }
        }

        // Clear handler map
        driverHandlerMap.remove(contextId);
    }

    @SuppressWarnings("unchecked")
    private void quitWebDriver(Object object, DriverHandler driverHandler,
            ScreenshotManager screenshotManager) {
        if (object != null) {
            if (List.class.isAssignableFrom(object.getClass())) {
                List<RemoteWebDriver> webDriverList = (List<RemoteWebDriver>) object;
                for (int i = 0; i < webDriverList.size(); i++) {
                    screenshotManager.makeScreenshot(webDriverList.get(i),
                            driverHandler.getName() + "_" + i);
                    webDriverList.get(i).quit();
                }

            } else {
                WebDriver webDriver = (WebDriver) object;
                if (driverHandler.getName() != null) {
                    screenshotManager.makeScreenshot(webDriver,
                            driverHandler.getName());
                }
                webDriver.quit();
            }
        }
    }

    private <T extends Object> T getValueFromContextId(Map<String, T> map,
            String contextId) {
        String newContextId = searchContextIdKeyInMap(map, contextId);
        return map.get(newContextId);
    }

    private String searchContextIdKeyInMap(Map<String, ?> map,
            String contextId) {
        if (!map.containsKey(contextId)) {
            int i = contextId.lastIndexOf('/');
            if (i != -1) {
                contextId = contextId.substring(0, i);
            }
        }
        return contextId;
    }

    @Override
    public boolean supportsTestTemplate(ExtensionContext context) {
        boolean allWebDriver = false;
        if (context.getTestMethod().isPresent()) {
            allWebDriver = !stream(
                    context.getTestMethod().get().getParameterTypes())
                            .map(s -> s.equals(WebDriver.class)
                                    || s.equals(RemoteWebDriver.class))
                            .collect(toList()).contains(false);
        }
        return allWebDriver;
    }

    @Override
    public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(
            ExtensionContext extensionContext) {
        String contextId = extensionContext.getUniqueId();
        try {
            // 1. By JSON content
            String browserJsonContent = getConfig()
                    .getBrowserTemplateJsonContent();
            if (browserJsonContent.isEmpty()) {
                // 2. By JSON file
                String browserJsonFile = getConfig()
                        .getBrowserTemplateJsonFile();
                if (browserJsonFile.startsWith(CLASSPATH_PREFIX)) {
                    String browserJsonInClasspath = browserJsonFile
                            .substring(CLASSPATH_PREFIX.length());
                    InputStream resourceAsStream = this.getClass()
                            .getResourceAsStream("/" + browserJsonInClasspath);

                    if (resourceAsStream != null) {
                        browserJsonContent = IOUtils.toString(resourceAsStream,
                                defaultCharset());
                    }

                } else {
                    browserJsonContent = new String(
                            readAllBytes(get(browserJsonFile)));
                }
            }
            if (!browserJsonContent.isEmpty()) {
                return new Gson()
                        .fromJson(browserJsonContent, BrowsersTemplate.class)
                        .getStream().map(b -> invocationContext(b, this));
            }

            // 3. By setter
            if (browserListList != null) {
                return browserListList.stream()
                        .map(b -> invocationContext(b, this));
            }
            if (browserListMap != null) {
                return Stream.of(
                        invocationContext(browserListMap.get(contextId), this));
            }

        } catch (IOException e) {
            throw new SeleniumJupiterException(e);
        }

        throw new SeleniumJupiterException(
                "No browser scenario registered for test template");
    }

    private synchronized TestTemplateInvocationContext invocationContext(
            List<Browser> template, SeleniumExtension parent) {
        return new TestTemplateInvocationContext() {
            @Override
            public String getDisplayName(int invocationIndex) {
                return template.toString();
            }

            @Override
            public List<Extension> getAdditionalExtensions() {
                return singletonList(new ParameterResolver() {
                    @Override
                    public boolean supportsParameter(
                            ParameterContext parameterContext,
                            ExtensionContext extensionContext) {
                        Class<?> type = parameterContext.getParameter()
                                .getType();
                        return type.equals(WebDriver.class)
                                || type.equals(RemoteWebDriver.class);
                    }

                    @Override
                    public Object resolveParameter(
                            ParameterContext parameterContext,
                            ExtensionContext extensionContext) {
                        String contextId = extensionContext.getUniqueId();
                        log.trace("Setting browser list {} for context id {}",
                                template, contextId);
                        parent.browserListMap.put(contextId, template);

                        return parent.resolveParameter(parameterContext,
                                extensionContext);
                    }
                });
            }
        };
    }

    private void addEntry(Map<String, Class<?>> map, String key,
            Class<?> value) {
        try {
            map.put(key, value);
        } catch (Exception e) {
            log.warn("Exception adding {}={} to handler map ({})", key, value,
                    e.getMessage());
        }
    }

    private boolean isTestTemplate(ExtensionContext extensionContext) {
        Optional<Method> testMethod = extensionContext.getTestMethod();
        return testMethod.isPresent()
                && testMethod.get().isAnnotationPresent(TestTemplate.class);
    }

    public void putBrowserList(String key, List<Browser> browserList) {
        this.browserListMap.put(key, browserList);
    }

    public void addBrowsers(Browser... browsers) {
        if (browserListList == null) {
            browserListList = new ArrayList<>();
        }
        browserListList.add(asList(browsers));
    }

    public Optional<String> getContainerId(WebDriver driver) {
        try {
            for (Map.Entry<String, Map<String, DockerContainer>> entry : containersMap
                    .entrySet()) {
                DockerContainer selenoidContainer = containersMap
                        .get(entry.getKey()).values().iterator().next();
                URL selenoidUrl = new URL(selenoidContainer.getContainerUrl());
                URL selenoidBaseUrl = new URL(selenoidUrl.getProtocol(),
                        selenoidUrl.getHost(), selenoidUrl.getPort(), "/");

                SelenoidService selenoidService = new SelenoidService(
                        selenoidBaseUrl.toString());
                Optional<String> containerId = selenoidService
                        .getContainerId(driver);

                if (containerId.isPresent()) {
                    return containerId;
                }
            }
            return empty();

        } catch (Exception e) {
            throw new SeleniumJupiterException(e);
        }
    }

    public DockerService getDockerService() {
        return dockerService;
    }

    public Config getConfig() {
        return config;
    }

    public void clearPreferences() {
        preferences.clear();
    }

}

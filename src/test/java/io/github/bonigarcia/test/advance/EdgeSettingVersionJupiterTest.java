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
package io.github.bonigarcia.test.advance;

//tag::snippet-in-doc[]
import static java.lang.System.setProperty;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.jupiter.api.BeforeAll;
// end::snippet-in-doc[]
import org.junit.jupiter.api.Disabled;
// tag::snippet-in-doc[]
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.openqa.selenium.edge.EdgeDriver;

import io.github.bonigarcia.SeleniumExtension;

// end::snippet-in-doc[]
@Disabled("Edge not available on Travis CI")
// tag::snippet-in-doc[]
@ExtendWith(SeleniumExtension.class)
public class EdgeSettingVersionJupiterTest {

    @BeforeAll
    static void setup() {
        setProperty("wdm.edgeVersion", "3.14393");
    }

    @Test
    void webrtcTest(EdgeDriver driver) {
        driver.get("http://www.seleniumhq.org/");
        assertThat(driver.getTitle(),
                containsString("A JUnit 5 extension for Selenium WebDriver"));
    }

}
// end::snippet-in-doc[]

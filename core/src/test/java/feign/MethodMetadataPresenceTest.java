/*
 * Copyright 2012-2023 The Feign Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package feign;

import static feign.assertj.MockWebServerAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import feign.FeignBuilderTest.TestInterface;
import feign.codec.Decoder;
import feign.codec.Encoder;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;

public class MethodMetadataPresenceTest {

  public final MockWebServer server = new MockWebServer();

  @Test
  void client() throws Exception {
    server.enqueue(new MockResponse().setBody("response data"));

    final String url = "http://localhost:" + server.getPort();
    final TestInterface api = Feign.builder()
        .client((request, options) -> {
          assertNotNull(request.requestTemplate());
          assertNotNull(request.requestTemplate().methodMetadata());
          assertNotNull(request.requestTemplate().feignTarget());
          return new Client.Default(null, null).execute(request, options);
        })
        .target(TestInterface.class, url);

    final Response response = api.codecPost("request data");
    assertThat(Util.toString(response.body().asReader(Util.UTF_8))).isEqualTo("response data");

    assertThat(server.takeRequest())
        .hasBody("request data");
  }

  @Test
  void encoder() throws Exception {
    server.enqueue(new MockResponse().setBody("response data"));

    final String url = "http://localhost:" + server.getPort();
    final TestInterface api = Feign.builder()
        .encoder((object, bodyType, template) -> {
          assertNotNull(template);
          assertNotNull(template.methodMetadata());
          assertNotNull(template.feignTarget());
          new Encoder.Default().encode(object, bodyType, template);
        })
        .target(TestInterface.class, url);

    final Response response = api.codecPost("request data");
    assertThat(Util.toString(response.body().asReader(Util.UTF_8))).isEqualTo("response data");

    assertThat(server.takeRequest())
        .hasBody("request data");
  }

  @Test
  void decoder() throws Exception {
    server.enqueue(new MockResponse().setBody("response data"));

    final String url = "http://localhost:" + server.getPort();
    final TestInterface api = Feign.builder()
        .decoder((response, type) -> {
          final RequestTemplate template = response.request().requestTemplate();
          assertNotNull(template);
          assertNotNull(template.methodMetadata());
          assertNotNull(template.feignTarget());
          return new Decoder.Default().decode(response, type);
        })
        .target(TestInterface.class, url);

    final Response response = api.codecPost("request data");
    assertThat(Util.toString(response.body().asReader(Util.UTF_8))).isEqualTo("response data");

    assertThat(server.takeRequest())
        .hasBody("request data");
  }

  @AfterEach
  void afterEachTest() throws IOException {
    server.close();
  }

}

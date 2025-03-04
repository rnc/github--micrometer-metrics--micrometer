/*
 * Copyright 2017 VMware, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micrometer.boot2.reactive.samples;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@SpringBootApplication
@RestController
public class PrometheusSample {

    private final WebClient webClient;

    public PrometheusSample(WebClient webClient) {
        this.webClient = webClient;
    }

    public static void main(String[] args) {
        new SpringApplicationBuilder(PrometheusSample.class).profiles("prometheus").run(args);
    }

    @GetMapping
    public Mono<String> hi() {
        return Mono.just("hi");
    }

    @GetMapping("callExternal")
    Mono<String> callExternal() {
        return this.webClient.get().uri("https://httpbin.org/headers").retrieve().bodyToMono(String.class);
    }
}

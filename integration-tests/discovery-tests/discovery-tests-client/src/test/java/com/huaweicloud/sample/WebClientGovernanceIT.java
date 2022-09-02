/*

 * Copyright (C) 2020-2022 Huawei Technologies Co., Ltd. All rights reserved.

 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.huaweicloud.sample;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

public class WebClientGovernanceIT {
  final String url = "http://127.0.0.1:10088";

  final RestTemplate template = new RestTemplate();

  @Test
  public void testWebClientRetry() {
    for (int i = 0; i < 1; i++) {
      String invocationID = UUID.randomUUID().toString();
      String result = template.getForObject(url + "/testWebClientRetry?invocationID={1}", String.class,
          invocationID);
      assertThat(result).isEqualTo("try times: 3");
    }
  }
}
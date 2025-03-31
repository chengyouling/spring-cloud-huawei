/*

 * Copyright (C) 2020-2025 Huawei Technologies Co., Ltd. All rights reserved.

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

package com.huaweicloud.governance.adapters.feign.options;

import org.springframework.cloud.openfeign.FeignBuilderCustomizer;

import feign.Feign.Builder;

public class FeignMethodRequestOptionsFactoryBuilder implements FeignBuilderCustomizer {
  @Override
  public void customize(Builder builder) {
    builder.invocationHandlerFactory(new FeignMethodRequestOptionsFactory());
  }
}

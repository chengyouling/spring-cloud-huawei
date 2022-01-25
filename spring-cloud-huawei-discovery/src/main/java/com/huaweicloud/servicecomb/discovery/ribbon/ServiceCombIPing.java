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

package com.huaweicloud.servicecomb.discovery.ribbon;

import com.netflix.loadbalancer.IPing;
import com.netflix.loadbalancer.Server;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * @Author GuoYl123
 * @Date 2020/4/17
 **/
public class ServiceCombIPing implements IPing {

  @Override
  public boolean isAlive(Server server) {
    try (Socket s = new Socket()) {
      s.connect(new InetSocketAddress(server.getHost(), server.getPort()), 3000);
    } catch (IOException e) {
      return false;
    }
    return true;
  }
}

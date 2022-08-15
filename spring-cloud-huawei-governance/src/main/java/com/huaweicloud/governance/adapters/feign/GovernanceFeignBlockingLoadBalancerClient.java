/*
 * Copyright 2013-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.huaweicloud.governance.adapters.feign;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.apache.servicecomb.governance.handler.FaultInjectionHandler;
import org.apache.servicecomb.governance.handler.InstanceBulkheadHandler;
import org.apache.servicecomb.governance.handler.InstanceIsolationHandler;
import org.apache.servicecomb.governance.handler.RetryHandler;
import org.apache.servicecomb.governance.handler.ext.ClientRecoverPolicy;
import org.apache.servicecomb.governance.marker.GovernanceRequest;
import org.apache.servicecomb.governance.policy.CircuitBreakerPolicy;
import org.apache.servicecomb.governance.policy.RetryPolicy;
import org.apache.servicecomb.http.client.common.HttpUtils;
import org.apache.servicecomb.injection.Fault;
import org.apache.servicecomb.injection.FaultInjectionDecorators;
import org.apache.servicecomb.injection.FaultInjectionDecorators.FaultInjectionDecorateCheckedSupplier;
import org.apache.servicecomb.service.center.client.DiscoveryEvents.PullInstanceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.CompletionContext;
import org.springframework.cloud.client.loadbalancer.DefaultRequest;
import org.springframework.cloud.client.loadbalancer.DefaultResponse;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.client.loadbalancer.LoadBalancerLifecycle;
import org.springframework.cloud.client.loadbalancer.LoadBalancerLifecycleValidator;
import org.springframework.cloud.client.loadbalancer.LoadBalancerProperties;
import org.springframework.cloud.client.loadbalancer.RequestData;
import org.springframework.cloud.client.loadbalancer.RequestDataContext;
import org.springframework.cloud.client.loadbalancer.ResponseData;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.util.Assert;

import com.huaweicloud.common.adapters.loadbalancer.RetryContext;
import com.huaweicloud.common.context.InvocationContext;
import com.huaweicloud.common.context.InvocationContextHolder;
import com.huaweicloud.common.event.EventManager;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.decorators.Decorators;
import io.github.resilience4j.decorators.Decorators.DecorateCheckedSupplier;
import io.github.resilience4j.retry.Retry;
import io.vavr.CheckedFunction0;
import feign.Client;
import feign.Request;
import feign.Request.Options;
import feign.Response;

/**
 * Support for retry, fault injection and instance isolation for FeignBlockingLoadBalancerClient.
 *
 * NOTICE: this class is copied from
 *    #org.springframework.cloud.openfeign.loadbalancer.FeignBlockingLoadBalancerClient
 *   and may need change when upgrading.
 */

@SuppressWarnings({"rawtypes", "unchecked", "deprecation"})
public class GovernanceFeignBlockingLoadBalancerClient implements Client {

  private static final Logger LOG = LoggerFactory.getLogger(GovernanceFeignBlockingLoadBalancerClient.class);

  private final Object faultObject = new Object();

  private final Client delegate;

  private final LoadBalancerClient loadBalancerClient;

  private final LoadBalancerProperties properties;
  private final LoadBalancerClientFactory loadBalancerClientFactory;

  private final RetryHandler retryHandler;

  private final FaultInjectionHandler faultInjectionHandler;

  private final InstanceIsolationHandler instanceIsolationHandler;

  private final InstanceBulkheadHandler instanceBulkheadHandler;

  private final ClientRecoverPolicy<Response> clientRecoverPolicy;

  public GovernanceFeignBlockingLoadBalancerClient(RetryHandler retryHandler,
      FaultInjectionHandler faultInjectionHandler,
      InstanceIsolationHandler instanceIsolationHandler,
      InstanceBulkheadHandler instanceBulkheadHandler,
      ClientRecoverPolicy<Response> clientRecoverPolicy,
      Client delegate, LoadBalancerClient loadBalancerClient,
      LoadBalancerProperties properties, LoadBalancerClientFactory loadBalancerClientFactory) {
    this.retryHandler = retryHandler;
    this.faultInjectionHandler = faultInjectionHandler;
    this.instanceIsolationHandler = instanceIsolationHandler;
    this.instanceBulkheadHandler = instanceBulkheadHandler;
    this.clientRecoverPolicy = clientRecoverPolicy;
    this.delegate = delegate;
    this.loadBalancerClient = loadBalancerClient;
    this.properties = properties;
    this.loadBalancerClientFactory = loadBalancerClientFactory;
  }

  @Override
  public Response execute(Request request, Request.Options options) {
    final URI originalUri = URI.create(request.url());

    GovernanceRequest governanceRequest = convert(request);
    governanceRequest.setServiceName(originalUri.getHost());

    CheckedFunction0<Response> next = () -> doExecute(originalUri, request, options, governanceRequest);

    DecorateCheckedSupplier<Response> dcs = Decorators.ofCheckedSupplier(next);

    try {
      addRetry(dcs, governanceRequest);

      // add Fault
      Fault fault = faultInjectionHandler.getActuator(governanceRequest);
      if (fault != null) {
        FaultInjectionDecorateCheckedSupplier<Object> faultInjectionDecorateCheckedSupplier =
            FaultInjectionDecorators.ofCheckedSupplier(() -> faultObject);
        faultInjectionDecorateCheckedSupplier.withFaultInjection(fault);
        try {
          Object result = faultInjectionDecorateCheckedSupplier.get();
          if (result != faultObject) {
            Map<String, Collection<String>> headers = new HashMap<>();
            headers.put("Content-Type", Arrays.asList("application/json"));
            return Response.builder().status(200)
                .request(request)
                .headers(headers)
                .body(HttpUtils.serialize(result).getBytes(
                    StandardCharsets.UTF_8)).build();
          }
        } catch (Throwable e) {
          return Response.builder().status(500).reason(e.getMessage()).request(request).build();
        }
      }

      // continue
      return dcs.get();
    } catch (Throwable e) {
      if (clientRecoverPolicy != null) {
        return clientRecoverPolicy.apply(e);
      }
      LOG.error("retry catch throwable", e);
      return Response.builder().status(500).reason(e.getMessage()).request(request).build();
    }
  }

  private Response doExecute(URI originalUri, Request request, Options options, GovernanceRequest governanceRequest)
      throws IOException {
    String serviceId = originalUri.getHost();
    Assert.state(serviceId != null, "Request URI does not contain a valid hostname: " + originalUri);
    String hint = getHint(serviceId);
    DefaultRequest<RequestDataContext> lbRequest = new DefaultRequest<>(
        new RequestDataContext(buildRequestData(request), hint));
    Set<LoadBalancerLifecycle> supportedLifecycleProcessors = LoadBalancerLifecycleValidator
        .getSupportedLifecycleProcessors(
            loadBalancerClientFactory.getInstances(serviceId, LoadBalancerLifecycle.class),
            RequestDataContext.class, ResponseData.class, ServiceInstance.class);
    supportedLifecycleProcessors.forEach(lifecycle -> lifecycle.onStart(lbRequest));
    ServiceInstance instance = loadBalancerClient.choose(serviceId, lbRequest);
    org.springframework.cloud.client.loadbalancer.Response<ServiceInstance> lbResponse = new DefaultResponse(
        instance);
    if (instance == null) {
      String message = "Load balancer does not contain an instance for the service " + serviceId;
      if (LOG.isWarnEnabled()) {
        LOG.warn(message);
      }
      supportedLifecycleProcessors.forEach(lifecycle -> lifecycle
          .onComplete(new CompletionContext<ResponseData, ServiceInstance, RequestDataContext>(
              CompletionContext.Status.DISCARD, lbRequest, lbResponse)));
      return Response.builder().request(request).status(HttpStatus.SERVICE_UNAVAILABLE.value())
          .body(message, StandardCharsets.UTF_8).build();
    }

    governanceRequest.setInstanceId(instance.getInstanceId());

    String reconstructedUrl = loadBalancerClient.reconstructURI(instance, originalUri).toString();
    Request newRequest = buildRequest(request, reconstructedUrl);
    return executeWithLoadBalancerLifecycleProcessing(governanceRequest, delegate, options, newRequest, lbRequest,
        lbResponse,
        supportedLifecycleProcessors);
  }

  protected Request buildRequest(Request request, String reconstructedUrl) {
    return Request.create(request.httpMethod(), reconstructedUrl, request.headers(), request.body(),
        request.charset(), request.requestTemplate());
  }

  // Visible for Sleuth instrumentation
  public Client getDelegate() {
    return delegate;
  }

  private String getHint(String serviceId) {
    String defaultHint = properties.getHint().getOrDefault("default", "default");
    String hintPropertyValue = properties.getHint().get(serviceId);
    return hintPropertyValue != null ? hintPropertyValue : defaultHint;
  }

  private Response executeWithLoadBalancerLifecycleProcessing(Client feignClient, Request.Options options,
      Request feignRequest, org.springframework.cloud.client.loadbalancer.Request lbRequest,
      org.springframework.cloud.client.loadbalancer.Response<ServiceInstance> lbResponse,
      Set<LoadBalancerLifecycle> supportedLifecycleProcessors, boolean loadBalanced)
      throws IOException {
    supportedLifecycleProcessors.forEach(lifecycle -> lifecycle.onStartRequest(lbRequest, lbResponse));
    try {
      Response response = feignClient.execute(feignRequest, options);
      if (loadBalanced) {
        supportedLifecycleProcessors.forEach(
            lifecycle -> lifecycle.onComplete(new CompletionContext<>(CompletionContext.Status.SUCCESS,
                lbRequest, lbResponse, buildResponseData(response))));
      }
      return response;
    } catch (Exception exception) {
      if (loadBalanced) {
        supportedLifecycleProcessors.forEach(lifecycle -> lifecycle.onComplete(
            new CompletionContext<>(CompletionContext.Status.FAILED, exception, lbRequest, lbResponse)));
      }
      throw exception;
    }
  }

  static ResponseData buildResponseData(Response response) {
    HttpHeaders responseHeaders = new HttpHeaders();
    response.headers().forEach((key, value) -> responseHeaders.put(key, new ArrayList<>(value)));
    return new ResponseData(HttpStatus.resolve(response.status()), responseHeaders, null,
        buildRequestData(response.request()));
  }

  static RequestData buildRequestData(Request request) {
    HttpHeaders requestHeaders = new HttpHeaders();
    request.headers().forEach((key, value) -> requestHeaders.put(key, new ArrayList<>(value)));
    return new RequestData(HttpMethod.resolve(request.httpMethod().name()), URI.create(request.url()),
        requestHeaders, null, new HashMap<>());
  }

  private Response executeWithLoadBalancerLifecycleProcessing(GovernanceRequest governanceRequest, Client feignClient,
      Request.Options options,
      Request feignRequest, org.springframework.cloud.client.loadbalancer.Request lbRequest,
      org.springframework.cloud.client.loadbalancer.Response<ServiceInstance> lbResponse,
      Set<LoadBalancerLifecycle> supportedLifecycleProcessors) {

    CheckedFunction0<Response> next = () -> executeWithLoadBalancerLifecycleProcessing(feignClient, options,
        feignRequest, lbRequest, lbResponse,
        supportedLifecycleProcessors, true);

    DecorateCheckedSupplier<Response> dcs = Decorators.ofCheckedSupplier(next);

    try {
      addInstanceBulkhead(dcs, governanceRequest);

      CircuitBreakerPolicy circuitBreakerPolicy = instanceIsolationHandler.matchPolicy(governanceRequest);
      if (circuitBreakerPolicy != null && circuitBreakerPolicy.isForceOpen()) {
        return Response.builder().status(503)
            .reason("Policy " + circuitBreakerPolicy.getName() + " forced open and deny requests").request(feignRequest)
            .build();
      }

      if (circuitBreakerPolicy != null && !circuitBreakerPolicy.isForceClosed()) {
        addInstanceIsolation(dcs, governanceRequest);
      }

      return dcs.get();
    } catch (Throwable e) {
      if (e instanceof CallNotPermittedException) {
        // when instance isolated, request to pull instances.
        LOG.error("instance isolated [{}]", governanceRequest.getInstanceId());
        EventManager.post(new PullInstanceEvent());
        return Response.builder().status(503).reason("instance isolated.").request(feignRequest).build();
      }

      if (e instanceof BulkheadFullException) {
        LOG.error("instance bulkhead is full [{}]", governanceRequest.getInstanceId());
        return Response.builder().status(503).reason("instance bulkhead is full.").request(feignRequest).build();
      }

      if (clientRecoverPolicy != null) {
        return clientRecoverPolicy.apply(e);
      }
      LOG.error("instance isolation catch throwable", e);
      return Response.builder().status(503).reason(e.getMessage()).request(feignRequest).build();
    }
  }

  private GovernanceRequest convert(Request request) {
    GovernanceRequest governanceRequest = new GovernanceRequest();
    try {
      URL url = new URL(request.url());
      governanceRequest.setUri(url.getPath());
      governanceRequest.setMethod(request.httpMethod().name());
      Map<String, String> headers = new HashMap<>(request.headers().size());
      request.headers().forEach((k, v) -> headers.put(k, v.iterator().next()));
      governanceRequest.setHeaders(headers);
      governanceRequest.setServiceName(URI.create(request.url()).getHost());
      return governanceRequest;
    } catch (MalformedURLException e) {
      return governanceRequest;
    }
  }

  private void addInstanceBulkhead(DecorateCheckedSupplier<Response> dcs, GovernanceRequest governanceRequest) {
    Bulkhead bulkhead = instanceBulkheadHandler.getActuator(governanceRequest);
    if (bulkhead != null) {
      dcs.withBulkhead(bulkhead);
    }
  }

  private void addInstanceIsolation(DecorateCheckedSupplier<Response> dcs, GovernanceRequest governanceRequest) {
    CircuitBreaker circuitBreaker = instanceIsolationHandler.getActuator(governanceRequest);
    if (circuitBreaker != null) {
      dcs.withCircuitBreaker(circuitBreaker);
    }
  }

  private void addRetry(DecorateCheckedSupplier<Response> dcs, GovernanceRequest request) {
    Retry retry = retryHandler.getActuator(request);
    if (retry != null) {
      dcs.withRetry(retry);
      RetryPolicy retryPolicy = retryHandler.matchPolicy(request);
      InvocationContext context = InvocationContextHolder.getOrCreateInvocationContext();
      RetryContext retryContext = new RetryContext(retryPolicy.getRetryOnSame());
      context.putLocalContext(RetryContext.RETRY_CONTEXT, retryContext);
    }
  }
}

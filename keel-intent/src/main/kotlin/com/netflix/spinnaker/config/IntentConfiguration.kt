/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.spinnaker.config

import com.netflix.spinnaker.keel.intent.SecurityGroupRule
import com.netflix.spinnaker.keel.intent.Trigger
import com.netflix.spinnaker.kork.jackson.ObjectMapperSubtypeConfigurer.ClassSubtypeLocator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration

@Configuration
@ComponentScan(basePackages = [
  "com.netflix.spinnaker.keel.intent",
  "com.netflix.spinnaker.keel.intent.processor",
  "com.netflix.spinnaker.keel.intent.processor.converter"
])
open class IntentConfiguration {

  @Bean open fun securityGroupSubTypeLocator() =
    ClassSubtypeLocator(SecurityGroupRule::class.java, listOf("com.netflix.spinnaker.keel.intent"))

  @Bean open fun pipelineTriggerSubTypeLocator() =
    ClassSubtypeLocator(Trigger::class.java, listOf("com.netflix.spinnaker.keel.intent"))
}
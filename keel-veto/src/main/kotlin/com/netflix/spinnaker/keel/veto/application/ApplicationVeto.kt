/*
 *
 * Copyright 2019 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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
package com.netflix.spinnaker.keel.veto.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.api.ResourceId
import com.netflix.spinnaker.keel.persistence.ApplicationVetoRepository
import com.netflix.spinnaker.keel.persistence.ResourceRepository
import com.netflix.spinnaker.keel.veto.Veto
import com.netflix.spinnaker.keel.veto.VetoResponse
import com.netflix.spinnaker.keel.veto.exceptions.MalformedMessageException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class ApplicationVeto(
  val applicationVetoRepository: ApplicationVetoRepository,
  val resourceRepository: ResourceRepository,
  val objectMapper: ObjectMapper
) : Veto {
  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  override fun check(resource: Resource<*>): VetoResponse {
    return checkApplication(resource.spec.application)
  }

  override fun check(resourceId: ResourceId, application: String): VetoResponse {
    return checkApplication(application)
  }

  override fun messageFormat() =
    mapOf(
      "application" to "String",
      "optedOut" to "Boolean"
    )

  override fun passMessage(message: Map<String, Any>) {
    log.debug("${this.javaClass.simpleName} received message: {}", message)
    try {
      val appInfo = objectMapper.convertValue(message, MessageFormat::class.java)
      if (appInfo.optedOut) {
        applicationVetoRepository.optOut(appInfo.application)
      } else {
        applicationVetoRepository.optIn(appInfo.application)
      }
    } catch (e: IllegalArgumentException) {
      throw MalformedMessageException(this.javaClass.simpleName, messageFormat())
    }
  }

  override fun currentRejections(): List<String> {
    return applicationVetoRepository.getAll().toList()
  }

  override fun currentRejectionsByApp(application: String): List<ResourceId> =
    resourceRepository.getByApplication(application).map { ResourceId(it) }

  private fun checkApplication(application: String): VetoResponse {
    if (applicationVetoRepository.appVetoed(application)) {
      return deniedResponse("Application $application has been opted out.")
    }
    return allowedResponse()
  }
}

data class MessageFormat(
  val application: String,
  val optedOut: Boolean
)

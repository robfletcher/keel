package com.netflix.spinnaker.keel.ec2.constraints

import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.api.actuation.Task
import com.netflix.spinnaker.keel.api.actuation.TaskLauncher
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.api.ec2.Capacity
import com.netflix.spinnaker.keel.artifacts.DebianArtifact
import com.netflix.spinnaker.keel.clouddriver.CloudDriverCache
import com.netflix.spinnaker.keel.clouddriver.CloudDriverService
import com.netflix.spinnaker.keel.clouddriver.ImageService
import com.netflix.spinnaker.keel.clouddriver.getLatestNamedImages
import com.netflix.spinnaker.keel.clouddriver.model.ActiveServerGroup
import com.netflix.spinnaker.keel.clouddriver.model.subnet
import com.netflix.spinnaker.keel.constraints.CanaryConstraintConfigurationProperties
import com.netflix.spinnaker.keel.constraints.CanaryConstraintDeployHandler
import com.netflix.spinnaker.keel.constraints.toStageBase
import com.netflix.spinnaker.keel.core.api.CanaryConstraint
import com.netflix.spinnaker.keel.core.parseMoniker
import com.netflix.spinnaker.keel.ec2.resolvers.ImageResolver
import com.netflix.spinnaker.keel.parseAppVersion
import com.netflix.spinnaker.keel.retrofit.isNotFound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import retrofit2.HttpException

class Ec2CanaryConstraintDeployHandler(
  private val defaults: CanaryConstraintConfigurationProperties,
  private val taskLauncher: TaskLauncher,
  private val cloudDriverService: CloudDriverService,
  private val cloudDriverCache: CloudDriverCache,
  private val imageService: ImageService,
  private val imageResolver: ImageResolver
) : CanaryConstraintDeployHandler {
  companion object {
    private val log by lazy { LoggerFactory.getLogger(Ec2CanaryConstraintDeployHandler::class.java) }
  }

  // TODO: The rest of Spinnaker should be refactored to refer to ec2 as ec2 instead of aws.
  //  But for now, `CanaryConstraint.source` points to a source cluster (including cloud
  //  provider) as Orca and Clouddriver would - that is, with "aws" as the provider when ec2
  //  is intended.
  override val supportedClouds = setOf("ec2", "aws")

  override suspend fun deployCanary(
    constraint: CanaryConstraint,
    artifact: DeliveryArtifact,
    version: String,
    deliveryConfig: DeliveryConfig,
    targetEnvironment: Environment,
    regions: Set<String>
  ): Map<String, Task> {
    require(artifact is DebianArtifact) {
      "Only Debian artifacts are supported for canaries on EC2"
    }

    val scope = CoroutineScope(GlobalScope.coroutineContext)
    val judge = "canary:${deliveryConfig.application}:${targetEnvironment.name}:${constraint.canaryConfigId}"

    val images = imageService.getLatestNamedImages(
      appVersion = version.replace("~", "_").parseAppVersion(),
      account = imageResolver.defaultImageAccount,
      regions = regions,
      baseOs = artifact.vmOptions.baseOs
    )

    val missingRegions = regions - images.keys
    if (missingRegions.isNotEmpty()) {
      error("Image not found for $version in all requested regions ($regions.joinToString())")
    }

    val source = getSourceServerGroups(deliveryConfig.application, constraint, deliveryConfig.serviceAccount)
    require(regions.all { source.containsKey(it) }) {
      "Source cluster ${constraint.source.cluster} not available in all canary regions"
    }

    val regionalJobs = regions.associateWith { region ->
      val sourceServerGroup = source.getValue(region)
      val image = images.getValue(region)
      constraint.toStageBase(
        cloudDriverCache = cloudDriverCache,
        metricsAccount = constraint.metricsAccount ?: defaults.metricsAccount,
        storageAccount = constraint.storageAccount ?: defaults.storageAccount,
        app = deliveryConfig.application,
        control = sourceServerGroup.toKayentaStageServerGroup(constraint.capacity, "baseline", image.imageName),
        experiment = sourceServerGroup.toKayentaStageServerGroup(constraint.capacity, "canary", image.imageName)
      )
    }

    @Suppress("UNCHECKED_CAST")
    return regionalJobs.keys.associateWith { region ->
      val description = "Canary $version for ${deliveryConfig.application}/environment " +
        "${targetEnvironment.name} in $region"

      scope.async {
        try {
          taskLauncher.submitJob(
            user = deliveryConfig.serviceAccount,
            application = deliveryConfig.application,
            notifications = targetEnvironment.notifications,
            subject = description,
            description = description,
            correlationId = "$judge:$region",
            stages = listOf(regionalJobs.getOrDefault(region, emptyMap()))
          )
        } catch (e: Exception) {
          log.error("Error launching orca canary for: ${description.toLowerCase()}")
          null
        }
      }
    }
      .mapValues { it.value.await() }
      .filterValues { it != null } as Map<String, Task>
  }

  private suspend fun getSourceServerGroups(
    app: String,
    constraint: CanaryConstraint,
    serviceAccount: String
  ): Map<String, ActiveServerGroup> =
    coroutineScope {
      constraint.regions.map { region ->
        async {
          try {
            cloudDriverService.activeServerGroup(
              user = serviceAccount,
              app = app,
              account = constraint.source.account,
              cluster = constraint.source.cluster,
              region = region,
              cloudProvider = constraint.source.cloudProvider
            )
          } catch (e: HttpException) {
            when (e.isNotFound) {
              true -> null
              else -> throw e
            }
          }
        }
      }
        .mapNotNull { it.await() }
        .associateBy { it.region }
    }

  private fun ActiveServerGroup.toKayentaStageServerGroup(
    capacity: Int,
    type: String,
    image: String
  ): Map<String, Any?> {
    val moniker = parseMoniker(name)
    val launchTemplateData = launchTemplate?.launchTemplateData
    return mutableMapOf(
      "application" to moniker.app,
      "stack" to moniker.stack,
      "freeFormDetails" to "${moniker.detail}-$type",
      "region" to region,
      "account" to accountName,
      "cloudProvider" to cloudProvider,
      "amiName" to image,
      "availabilityZones" to mapOf(region to zones),
      "capacity" to Capacity(capacity, capacity, capacity),
      "ebsOptimized" to (launchConfig?.ebsOptimized ?: launchTemplateData!!.ebsOptimized),
      "healthCheckGracePeriod" to asg.healthCheckGracePeriod,
      "healthCheckType" to asg.healthCheckType,
      "iamRole" to (launchConfig?.iamInstanceProfile
        ?: launchTemplateData!!.iamInstanceProfile.name),
      "instanceMonitoring" to (launchConfig?.instanceMonitoring?.enabled
        ?: launchTemplateData!!.monitoring.enabled),
      "instanceType" to (launchConfig?.instanceType ?: launchTemplateData!!.instanceType),
      "keyPair" to (launchConfig?.keyName ?: launchTemplateData!!.keyName),
      "loadBalancers" to loadBalancers,
      "targetGroups" to targetGroups,
      "securityGroups" to securityGroups,
      "strategy" to "redblack",
      "subnetType" to subnet(cloudDriverCache),
      "suspendedProcesses" to asg.suspendedProcesses,
      "useSourceCapacity" to false
    )
  }
}

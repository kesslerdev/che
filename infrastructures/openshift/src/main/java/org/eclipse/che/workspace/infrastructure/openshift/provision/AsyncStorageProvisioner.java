/*
 * Copyright (c) 2012-2018 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package org.eclipse.che.workspace.infrastructure.openshift.provision;

import static java.lang.String.format;
import static java.lang.String.valueOf;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.eclipse.che.api.workspace.shared.Constants.ASYNC_PERSIST_ATTRIBUTE;
import static org.eclipse.che.workspace.infrastructure.kubernetes.Warnings.NOT_ABLE_TO_PROVISION_SSH_KEYS;
import static org.eclipse.che.workspace.infrastructure.kubernetes.Warnings.NOT_ABLE_TO_PROVISION_SSH_KEYS_MESSAGE;
import static org.eclipse.che.workspace.infrastructure.kubernetes.namespace.pvc.EphemeralWorkspaceUtility.isEphemeral;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableMap;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ConfigMapVolumeSourceBuilder;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.IntOrStringBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimVolumeSourceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.fabric8.kubernetes.api.model.ServiceSpec;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Named;
import org.eclipse.che.api.core.ConflictException;
import org.eclipse.che.api.core.ServerException;
import org.eclipse.che.api.core.model.workspace.runtime.RuntimeIdentity;
import org.eclipse.che.api.ssh.server.SshManager;
import org.eclipse.che.api.ssh.server.model.impl.SshPairImpl;
import org.eclipse.che.api.ssh.shared.model.SshPair;
import org.eclipse.che.api.workspace.server.model.impl.WarningImpl;
import org.eclipse.che.api.workspace.server.spi.InfrastructureException;
import org.eclipse.che.workspace.infrastructure.kubernetes.Names;
import org.eclipse.che.workspace.infrastructure.kubernetes.namespace.KubernetesObjectUtil;
import org.eclipse.che.workspace.infrastructure.openshift.OpenShiftClientFactory;
import org.eclipse.che.workspace.infrastructure.openshift.environment.OpenShiftEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configure environment for Async Storage feature (details described in issue
 * https://github.com/eclipse/che/issues/15384) This environment will allow backup on workspace stop
 * event and restore on restart created earlier. <br>
 * Will apply only in case workspace has attributes: asyncPersist: true - persistVolumes:
 * false.</br> In case workspace has attributes: asyncPersist: true - persistVolumes: true will
 * throw exception.</br> Feature enabled only for 'common' PVC strategy, in other cases will throw
 * exception.</br> During provision will be created: - storage Pod - service for rsync connection
 * via SSH - configmap, with public part of SSH key - PVC for storing backups;
 */
public class AsyncStorageProvisioner {

  private static final int SERVICE_PORT = 2222;
  private static final String AUTHORIZED_KEYS = "authorized_keys";
  static final String ASYNC_STORAGE = "async-storage";
  static final String ASYNC_STORAGE_CONFIG = "async-storage-config";
  static final String ASYNC_STORAGE_CLAIM = "async-storage-claim";
  private static final String ASYNC_STORAGE_DATA_PATH = "/var/lib/storage/data/";
  private static final String SSH_KEY_PATH = "/.ssh/" + AUTHORIZED_KEYS;
  static final String SSH_KEY_NAME = "rsync-via-ssh";
  private static final String CONFIG_MAP_VOLUME_NAME = "async-storage-configvolume";
  private static final String STORAGE_VOLUME = "async-storage-data";

  private static final Logger LOG = LoggerFactory.getLogger(AsyncStorageProvisioner.class);

  private final String pvcQuantity;
  private final String storageImage;
  private final String accessMode;
  private final String strategy;
  private final SshManager sshManager;
  private final OpenShiftClientFactory clientFactory;

  @Inject
  public AsyncStorageProvisioner(
      @Named("che.infra.kubernetes.pvc.quantity") String pvcQuantity,
      @Named("che.infra.kubernetes.async.storage.image") String image,
      @Named("che.infra.kubernetes.pvc.access_mode") String accessMode,
      @Named("che.infra.kubernetes.pvc.strategy") String strategy,
      SshManager sshManager,
      OpenShiftClientFactory openShiftClientFactory) {
    this.pvcQuantity = pvcQuantity;
    this.storageImage = image;
    this.accessMode = accessMode;
    this.strategy = strategy;
    this.sshManager = sshManager;
    this.clientFactory = openShiftClientFactory;
  }

  public void provision(OpenShiftEnvironment osEnv, RuntimeIdentity identity)
      throws InfrastructureException {
    if (!"true".equals(osEnv.getAttributes().get(ASYNC_PERSIST_ATTRIBUTE))) {
      return;
    }

    if ("true".equals(osEnv.getAttributes().get(ASYNC_PERSIST_ATTRIBUTE))
        && !"common".equals(strategy)) {
      String message =
          format(
              "Workspace configuration not valid: Asynchronous storage available only for 'common' PVC strategy, but got %s",
              strategy);
      LOG.warn(message);
      osEnv.addWarning(new WarningImpl(4200, message));
      throw new InfrastructureException(message);
    }

    if ("true".equals(osEnv.getAttributes().get(ASYNC_PERSIST_ATTRIBUTE))
        && !isEphemeral(osEnv.getAttributes())) {
      String message =
          "Workspace configuration not valid: Asynchronous storage available only if attribute 'persistVolumes' set to false";
      LOG.warn(message);
      osEnv.addWarning(new WarningImpl(4200, message));
      throw new InfrastructureException(message);
    }

    String namespace = identity.getInfrastructureNamespace();
    KubernetesClient oc = clientFactory.create(identity.getWorkspaceId());

    boolean isPvcExist =
        oc.persistentVolumeClaims()
            .inNamespace(namespace)
            .list()
            .getItems()
            .stream()
            .anyMatch(
                (Predicate<PersistentVolumeClaim>)
                    pvc -> pvc.getMetadata().getName().equals(ASYNC_STORAGE_CLAIM));

    if (!isPvcExist) {
      PersistentVolumeClaim pvc =
          KubernetesObjectUtil.newPVC(ASYNC_STORAGE_CLAIM, accessMode, pvcQuantity);
      oc.persistentVolumeClaims().inNamespace(namespace).create(pvc);
    }

    String configMapName = namespace + ASYNC_STORAGE_CONFIG;
    boolean isMapExist =
        oc.configMaps()
            .inNamespace(namespace)
            .list()
            .getItems()
            .stream()
            .anyMatch(
                (Predicate<ConfigMap>)
                    configMap -> configMap.getMetadata().getName().equals(configMapName));

    if (!isMapExist) {
      ConfigMap configMap = createConfigMap(namespace, configMapName, identity, osEnv);
      oc.configMaps().inNamespace(namespace).create(configMap);
    }

    boolean isPodExist =
        oc.pods()
            .inNamespace(namespace)
            .list()
            .getItems()
            .stream()
            .anyMatch((Predicate<Pod>) pod -> pod.getMetadata().getName().equals(ASYNC_STORAGE));

    if (!isPodExist) {
      Pod pod = createStoragePod(namespace, configMapName);
      oc.pods().inNamespace(namespace).create(pod);
    }

    boolean isServiceExist =
        oc.services()
            .inNamespace(namespace)
            .list()
            .getItems()
            .stream()
            .anyMatch(
                (Predicate<Service>)
                    service -> service.getMetadata().getName().equals(ASYNC_STORAGE));

    if (!isServiceExist) {
      Service service = createStorageService(namespace);
      oc.services().inNamespace(namespace).create(service);
    }
  }

  /** Get or create new pair of SSH keys, this is need for securing rsync connection */
  private List<SshPairImpl> getOrCreateSshPairs(
      RuntimeIdentity identity, OpenShiftEnvironment osEnv) throws InfrastructureException {
    List<SshPairImpl> sshPairs;
    try {
      sshPairs = sshManager.getPairs(identity.getOwnerId(), "internal");
    } catch (ServerException e) {
      String message = format("Unable to get SSH Keys. Cause: %s", e.getMessage());
      LOG.warn(message);
      osEnv.addWarning(
          new WarningImpl(
              NOT_ABLE_TO_PROVISION_SSH_KEYS,
              format(NOT_ABLE_TO_PROVISION_SSH_KEYS_MESSAGE, message)));
      throw new InfrastructureException(e);
    }
    if (sshPairs.isEmpty()) {
      try {
        sshPairs =
            singletonList(sshManager.generatePair(identity.getOwnerId(), "internal", SSH_KEY_NAME));
      } catch (ServerException | ConflictException e) {
        String message =
            format(
                "Unable to generate the SSH key for async storage service. Cause: %S",
                e.getMessage());
        LOG.warn(message);
        osEnv.addWarning(
            new WarningImpl(
                NOT_ABLE_TO_PROVISION_SSH_KEYS,
                format(NOT_ABLE_TO_PROVISION_SSH_KEYS_MESSAGE, message)));
        throw new InfrastructureException(e);
      }
    }
    return sshPairs;
  }

  /** Create configmap with public part of SSH key */
  private ConfigMap createConfigMap(
      String namespace, String configMapName, RuntimeIdentity identity, OpenShiftEnvironment osEnv)
      throws InfrastructureException {
    List<SshPairImpl> sshPairs = getOrCreateSshPairs(identity, osEnv);
    if (sshPairs == null) {
      return null;
    }
    SshPair sshPair = sshPairs.get(0);
    Map<String, String> sshConfigData = ImmutableMap.of(AUTHORIZED_KEYS, sshPair.getPublicKey());
    return new ConfigMapBuilder()
        .withNewMetadata()
        .withName(configMapName)
        .withNamespace(namespace)
        .endMetadata()
        .withData(sshConfigData)
        .build();
  }

  /**
   * Create storage Pod with container with mounted volume for storing project source backups, SSH
   * key and exposed port for rsync connection
   */
  private Pod createStoragePod(String namespace, String configMap) {
    String containerName = Names.generateName(ASYNC_STORAGE);

    Volume storageVolume =
        new VolumeBuilder()
            .withName(STORAGE_VOLUME)
            .withPersistentVolumeClaim(
                new PersistentVolumeClaimVolumeSourceBuilder()
                    .withClaimName(ASYNC_STORAGE_CLAIM)
                    .withReadOnly(false)
                    .build())
            .build();

    Volume sshKeyVolume =
        new VolumeBuilder()
            .withName(CONFIG_MAP_VOLUME_NAME)
            .withConfigMap(new ConfigMapVolumeSourceBuilder().withName(configMap).build())
            .build();

    VolumeMount storageVolumeMount =
        new VolumeMountBuilder()
            .withMountPath(ASYNC_STORAGE_DATA_PATH)
            .withName(STORAGE_VOLUME)
            .withReadOnly(false)
            .build();

    VolumeMount sshVolumeMount =
        new VolumeMountBuilder()
            .withMountPath(SSH_KEY_PATH)
            .withSubPath(AUTHORIZED_KEYS)
            .withName(CONFIG_MAP_VOLUME_NAME)
            .withReadOnly(true)
            .build();

    Container container =
        new ContainerBuilder()
            .withName(containerName)
            .withImage(storageImage)
            .withNewResources()
            .addToLimits("memory", new Quantity("512Mi"))
            .addToRequests("memory", new Quantity("256Mi"))
            .endResources()
            .withPorts(
                new ContainerPortBuilder()
                    .withContainerPort(SERVICE_PORT)
                    .withProtocol("TCP")
                    .build())
            .withVolumeMounts(storageVolumeMount, sshVolumeMount)
            .build();

    PodSpecBuilder podSpecBuilder = new PodSpecBuilder();
    PodSpec podSpec =
        podSpecBuilder.withContainers(container).withVolumes(storageVolume, sshKeyVolume).build();

    return new PodBuilder()
        .withApiVersion("v1")
        .withKind("Pod")
        .withNewMetadata()
        .withName(ASYNC_STORAGE)
        .withNamespace(namespace)
        .withLabels(singletonMap("app", ASYNC_STORAGE))
        .endMetadata()
        .withSpec(podSpec)
        .build();
  }

  /** Create service for serving rsync connection */
  private Service createStorageService(String namespace) {
    ObjectMeta meta = new ObjectMeta();
    meta.setName(ASYNC_STORAGE);
    meta.setNamespace(namespace);

    IntOrString targetPort =
        new IntOrStringBuilder().withIntVal(SERVICE_PORT).withStrVal(valueOf(SERVICE_PORT)).build();

    ServicePort port =
        new ServicePortBuilder()
            .withName("RSYNC_PORT")
            .withProtocol("TCP")
            .withPort(SERVICE_PORT)
            .withTargetPort(targetPort)
            .build();
    ServiceSpec spec = new ServiceSpec();
    spec.setPorts(singletonList(port));
    spec.setSelector(singletonMap("app", ASYNC_STORAGE));

    Service service = new Service();
    service.setApiVersion("v1");
    service.setKind("Service");
    service.setMetadata(meta);
    service.setSpec(spec);

    return service;
  }
}

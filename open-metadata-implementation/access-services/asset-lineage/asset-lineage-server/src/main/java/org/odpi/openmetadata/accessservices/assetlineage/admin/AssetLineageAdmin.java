/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright Contributors to the ODPi Egeria project. */

package org.odpi.openmetadata.accessservices.assetlineage.admin;


import org.odpi.openmetadata.accessservices.assetlineage.auditlog.AssetLineageAuditCode;
import org.odpi.openmetadata.accessservices.assetlineage.listeners.AssetLineageOMRSTopicListener;
import org.odpi.openmetadata.accessservices.assetlineage.outtopic.AssetLineagePublisher;
import org.odpi.openmetadata.accessservices.assetlineage.server.AssetLineageServicesInstance;
import org.odpi.openmetadata.accessservices.assetlineage.util.AssetLineageTypesValidator;
import org.odpi.openmetadata.accessservices.assetlineage.util.Converter;
import org.odpi.openmetadata.adminservices.configuration.properties.AccessServiceConfig;
import org.odpi.openmetadata.adminservices.configuration.registration.AccessServiceAdmin;
import org.odpi.openmetadata.adminservices.configuration.registration.AccessServiceDescription;
import org.odpi.openmetadata.adminservices.ffdc.exception.OMAGConfigurationErrorException;
import org.odpi.openmetadata.frameworks.auditlog.AuditLog;
import org.odpi.openmetadata.frameworks.connectors.properties.beans.Connection;
import org.odpi.openmetadata.repositoryservices.connectors.omrstopic.OMRSTopicConnector;
import org.odpi.openmetadata.repositoryservices.connectors.openmetadatatopic.OpenMetadataTopicConnector;
import org.odpi.openmetadata.repositoryservices.connectors.stores.metadatacollectionstore.repositoryconnector.OMRSRepositoryConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * AssetLineageAdmin is the class that is called by the OMAG Server to initialize and terminate
 * the Asset Lineage OMAS. The initialization call provides this OMAS with resources from the
 * Open Metadata Repository Services.
 */
public class AssetLineageAdmin extends AccessServiceAdmin {

    private static final Logger log = LoggerFactory.getLogger(AssetLineageAdmin.class);
    private AuditLog auditLog;
    private AssetLineageServicesInstance instance;
    private String serverName;

    /**
     * Initialize the access service.
     *
     * @param accessServiceConfigurationProperties specific configuration properties for this access service.
     * @param enterpriseOMRSTopicConnector         connector for receiving OMRS Events from the cohorts
     * @param repositoryConnector                  connector for querying the cohort repositories
     * @param auditLog                             audit log component for logging messages.
     * @param serverUserName                       user id to use on OMRS calls where there is no end user.
     * @throws OMAGConfigurationErrorException invalid parameters in the configuration properties.
     */
    @Override
    public void initialize(AccessServiceConfig accessServiceConfigurationProperties, OMRSTopicConnector enterpriseOMRSTopicConnector,
                           OMRSRepositoryConnector repositoryConnector, AuditLog auditLog, String serverUserName) throws
            OMAGConfigurationErrorException {
        final String actionDescription = "initialize";
        auditLog.logMessage(actionDescription, AssetLineageAuditCode.SERVICE_INITIALIZING.getMessageDefinition());

        try {
            this.auditLog = auditLog;
            Map<String, Object> accessServiceOptions = accessServiceConfigurationProperties.getAccessServiceOptions();
            List<String> supportedZones = this.extractSupportedZones(accessServiceOptions,
                    accessServiceConfigurationProperties.getAccessServiceName(), auditLog);

            AssetLineageTypesValidator assetLineageTypesValidator = new AssetLineageTypesValidator(repositoryConnector.getRepositoryHelper(),
                    accessServiceOptions);
            this.instance = new AssetLineageServicesInstance(repositoryConnector, supportedZones, serverUserName, auditLog,
                    accessServiceConfigurationProperties.getAccessServiceOutTopic(), assetLineageTypesValidator);
            this.serverName = instance.getServerName();

            Connection outTopicConnection = accessServiceConfigurationProperties.getAccessServiceOutTopic();

            if (outTopicConnection != null) {
                OpenMetadataTopicConnector outTopicConnector = super.getOutTopicEventBusConnector(outTopicConnection,
                        accessServiceConfigurationProperties.getAccessServiceName(), auditLog);

                Converter converter = new Converter(repositoryConnector.getRepositoryHelper());
                AssetLineagePublisher publisher = new AssetLineagePublisher(outTopicConnector, serverName, serverUserName, accessServiceOptions);
                AssetLineageOMRSTopicListener omrsTopicListener = new AssetLineageOMRSTopicListener(converter, serverName, publisher,
                        assetLineageTypesValidator, auditLog);

                super.registerWithEnterpriseTopic(accessServiceConfigurationProperties.getAccessServiceName(), serverName,
                        enterpriseOMRSTopicConnector, omrsTopicListener, auditLog);
                this.instance.setAssetLineagePublisher(omrsTopicListener.getPublisher());
            }

            auditLog.logMessage(actionDescription, AssetLineageAuditCode.SERVICE_INITIALIZED.getMessageDefinition(serverName));
        } catch (Exception error) {
            log.error("The Asset Lineage OMAS could not be started", error);
            auditLog.logException(actionDescription,
                    AssetLineageAuditCode.SERVICE_INSTANCE_FAILURE.getMessageDefinition(error.getMessage(), serverName), error);

            super.throwUnexpectedInitializationException(actionDescription, AccessServiceDescription.ASSET_LINEAGE_OMAS.getAccessServiceFullName(),
                    error);
        }
    }

    /**
     * Shutdown the access service.
     */
    public void shutdown() {
        if (instance != null) {
            instance.shutdown();
        }

        if (auditLog != null) {
            final String actionDescription = "shutdown";

            auditLog.logMessage(actionDescription, AssetLineageAuditCode.SERVICE_SHUTDOWN.getMessageDefinition(serverName));
        }
    }
}

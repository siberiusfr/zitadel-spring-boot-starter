package tn.cyberious.zitadel.autoconfigure

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import tn.cyberious.zitadel.config.ZitadelProperties
import tn.cyberious.zitadel.service.ZitadelManagementService

@AutoConfiguration
@EnableConfigurationProperties(ZitadelProperties::class)
class ZitadelAutoConfiguration {

    @Bean
    @ConditionalOnExpression(
        "'\${cyberious.zitadel.service-account-key-json:}'.length() > 0 " +
            "|| '\${cyberious.zitadel.personal-access-token:}'.length() > 0"
    )
    fun zitadelManagementService(properties: ZitadelProperties): ZitadelManagementService {
        return ZitadelManagementService(properties)
    }
}

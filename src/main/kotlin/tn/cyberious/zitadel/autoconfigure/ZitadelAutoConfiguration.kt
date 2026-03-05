package tn.cyberious.zitadel.autoconfigure

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import tn.cyberious.zitadel.config.ZitadelProperties
import tn.cyberious.zitadel.service.ZitadelManagementService

@AutoConfiguration
@EnableConfigurationProperties(ZitadelProperties::class)
class ZitadelAutoConfiguration {

    @Bean
    @ConditionalOnProperty(
        name = ["cyberious.zitadel.service-account-key-json"],
        matchIfMissing = false
    )
    fun zitadelManagementService(properties: ZitadelProperties): ZitadelManagementService {
        return ZitadelManagementService(properties)
    }
}

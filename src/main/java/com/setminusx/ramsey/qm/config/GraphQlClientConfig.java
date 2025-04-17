package com.setminusx.ramsey.qm.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.client.HttpGraphQlClient;

@Configuration
public class GraphQlClientConfig {

    @Bean
    public HttpGraphQlClient graphQlClient(RamseyConfig ramseyConfig) {
        String graphqlUrl = ramseyConfig.getMw().getHost() + "/graphql";
        return HttpGraphQlClient.builder()
                .url(graphqlUrl)
                .build();
    }
}

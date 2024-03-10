/*
 * Copyright (c) 2024 - present - Yupiik SAS - https://www.yupiik.com
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.hcms.service.persistence;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.order.Order;
import io.yupiik.fusion.framework.build.api.scanning.Bean;
import io.yupiik.fusion.observability.metrics.MetricsRegistry;
import io.yupiik.hcms.configuration.HCMSConfiguration;
import org.apache.tomcat.jdbc.pool.DataSource;
import org.apache.tomcat.jdbc.pool.PoolProperties;

@DefaultScoped
class DataSourceProducer {
    @Bean
    @Order(2_000)
    @ApplicationScoped
    public DataSource dataSource(final HCMSConfiguration configuration, final MetricsRegistry metrics) {
        final var conf = configuration.database();
        final var properties = new PoolProperties();
        properties.setDriverClassName(conf.driver());
        properties.setUrl(conf.url());
        properties.setUsername(conf.username());
        properties.setPassword(conf.password());
        properties.setTestOnBorrow(conf.testOnBorrow());
        properties.setTestOnReturn(conf.testOnReturn());
        properties.setTestWhileIdle(conf.testWhileIdle());
        properties.setTimeBetweenEvictionRunsMillis(conf.timeBetweenEvictionRuns());
        properties.setMinEvictableIdleTimeMillis(conf.minEvictableIdleTime());
        properties.setValidationQuery(conf.validationQuery());
        properties.setValidationQueryTimeout(conf.validationQueryTimeout());
        properties.setMinIdle(conf.minIdle());
        properties.setMaxActive(conf.maxActive());
        properties.setRemoveAbandoned(conf.removeAbandoned());
        properties.setDefaultAutoCommit(conf.defaultAutoCommit());
        properties.setLogAbandoned(conf.logAbandoned());
        properties.setRemoveAbandonedTimeout(conf.removeAbandonedTimeout());
        properties.setDefaultReadOnly(false);
        properties.setCommitOnReturn(false);
        properties.setRollbackOnReturn(false);

        final var dataSource = new DataSource(properties);
        metrics.registerReadOnlyGauge("datasource_connections_count", "unit", dataSource::getSize);
        metrics.registerReadOnlyGauge(
                "datasource_connectionsdatasource_connections_active_count", "unit", dataSource::getNumActive);
        metrics.registerReadOnlyGauge("datasource_connections_idle_count", "unit", dataSource::getNumIdle);
        metrics.registerReadOnlyGauge("datasource_connections_created_count", "unit", dataSource::getCreatedCount);

        return dataSource;
    }
}

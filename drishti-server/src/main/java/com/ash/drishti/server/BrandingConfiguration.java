/*
 * Project Drishti · Any data. Any domain. One grammar.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.drishti.server;

import com.ash.drishti.common.Branding;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** {@code drishti.branding.*}: the product name and legal notices (application.yaml), as one bean. */
@Configuration(proxyBeanMethods = false)
public class BrandingConfiguration {

    @Bean
    @ConfigurationProperties("drishti.branding")
    public BrandingProperties brandingProperties() {
        return new BrandingProperties();
    }

    @Bean
    public Branding branding(BrandingProperties p) {
        return new Branding(p.getProduct(), p.getTagline(), p.getOwner(), p.getCopyright(), p.getNotice());
    }

    /** Bound from configuration; read once into {@link Branding}. */
    public static class BrandingProperties {
        private String product;
        private String tagline;
        private String owner;
        private String copyright;
        private String notice;

        public String getProduct() {
            return product;
        }

        public void setProduct(String product) {
            this.product = product;
        }

        public String getTagline() {
            return tagline;
        }

        public void setTagline(String tagline) {
            this.tagline = tagline;
        }

        public String getOwner() {
            return owner;
        }

        public void setOwner(String owner) {
            this.owner = owner;
        }

        public String getCopyright() {
            return copyright;
        }

        public void setCopyright(String copyright) {
            this.copyright = copyright;
        }

        public String getNotice() {
            return notice;
        }

        public void setNotice(String notice) {
            this.notice = notice;
        }
    }
}

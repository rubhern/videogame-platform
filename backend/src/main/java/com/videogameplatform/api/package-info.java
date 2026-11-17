/** Owns manual inbound HTTP delivery and isolated build-generated OpenAPI transport types. */
@org.springframework.modulith.ApplicationModule(
        displayName = "API Delivery",
        allowedDependencies = {
            "catalogue::application",
            "catalogue::acquisition",
            "catalogue::administration",
            "catalogue::cover",
            "catalogue::releases",
            "catalogue::search",
            "catalogue::rankings",
            "catalogue::platformgames",
            "catalogue::details",
            "catalogue::preferences",
            "catalogue::settings",
            "identity::application",
            "ratings::application",
            "feedback::application"
        })
package com.videogameplatform.api;

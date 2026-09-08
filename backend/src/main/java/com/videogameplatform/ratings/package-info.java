/** Owns the personal and aggregate ratings boundary. */
@org.springframework.modulith.ApplicationModule(
        displayName = "Ratings",
        allowedDependencies = {
            "catalogue::application",
            "catalogue::details",
            "catalogue::releases"
        })
package com.videogameplatform.ratings;

package com.videogameplatform.catalogue.application.settings;

/**
 * The effective product settings, read from durable storage on every call so a change applies from
 * the next operation on, in every application instance, without restart. A caller reads once per
 * operation and uses that snapshot throughout. A missing or invalid stored value never fails a
 * read: the setting takes its default.
 */
public interface CurrentProductSettings {
    ProductSettings current();
}

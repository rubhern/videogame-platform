resource "oci_core_volume" "data" {
  availability_domain  = var.availability_domain
  compartment_id       = var.compartment_ocid
  display_name         = "${var.name_prefix}-data"
  is_auto_tune_enabled = false
  size_in_gbs          = var.data_volume_size_gb
  vpus_per_gb          = 10
  freeform_tags        = var.freeform_tags

  lifecycle {
    prevent_destroy = true
  }
}

resource "oci_objectstorage_bucket" "backups" {
  compartment_id        = var.compartment_ocid
  namespace             = var.namespace
  name                  = "${var.name_prefix}-backups"
  access_type           = "NoPublicAccess"
  auto_tiering          = "Disabled"
  object_events_enabled = false
  storage_tier          = "Standard"
  versioning            = "Enabled"
  freeform_tags         = var.freeform_tags

  lifecycle {
    prevent_destroy = true
  }
}

resource "oci_objectstorage_object_lifecycle_policy" "backups" {
  bucket    = oci_objectstorage_bucket.backups.name
  namespace = var.namespace

  rules {
    action      = "DELETE"
    is_enabled  = true
    name        = "delete-expired-backups"
    target      = "objects"
    time_amount = var.backup_retention_days
    time_unit   = "DAYS"

    object_name_filter {
      inclusion_patterns = ["backups/*"]
    }
  }

  rules {
    action      = "DELETE"
    is_enabled  = true
    name        = "delete-expired-backup-versions"
    target      = "previous-object-versions"
    time_amount = var.backup_retention_days
    time_unit   = "DAYS"

    object_name_filter {
      inclusion_patterns = ["backups/*"]
    }
  }

  rules {
    action      = "ABORT"
    is_enabled  = true
    name        = "abort-incomplete-uploads"
    target      = "multipart-uploads"
    time_amount = 1
    time_unit   = "DAYS"
  }
}

resource "oci_kms_vault" "runtime" {
  compartment_id = var.compartment_ocid
  display_name   = "${var.name_prefix}-runtime"
  vault_type     = "DEFAULT"
  freeform_tags  = var.freeform_tags

  lifecycle {
    prevent_destroy = true
  }
}

resource "oci_kms_key" "runtime" {
  compartment_id      = var.compartment_ocid
  display_name        = "${var.name_prefix}-runtime"
  management_endpoint = oci_kms_vault.runtime.management_endpoint
  protection_mode     = "SOFTWARE"
  freeform_tags       = var.freeform_tags

  key_shape {
    algorithm = "AES"
    length    = 32
  }

  lifecycle {
    prevent_destroy = true
  }
}

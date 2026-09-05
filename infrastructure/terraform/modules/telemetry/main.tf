resource "oci_ons_notification_topic" "infrastructure" {
  compartment_id = var.compartment_ocid
  name           = "${var.name_prefix}-infrastructure"
  description    = "Bounded infrastructure and Always Free guardrail alarms"
  freeform_tags  = var.freeform_tags
}

resource "oci_monitoring_alarm" "a1_low_cpu" {
  compartment_id        = var.compartment_ocid
  destinations          = [oci_ons_notification_topic.infrastructure.id]
  display_name          = "${var.name_prefix}-a1-low-cpu"
  is_enabled            = true
  metric_compartment_id = var.compartment_ocid
  namespace             = "oci_computeagent"
  pending_duration      = "PT1H"
  query                 = "CpuUtilization[1h]{resourceId = \"${var.instance_ocid}\"}.mean() < 20"
  resolution            = "1m"
  severity              = "WARNING"
  body                  = "A1 CPU is below the OCI Always Free idle threshold; inspect the complete seven-day CPU, network, and memory criteria."
  freeform_tags         = var.freeform_tags
}

resource "oci_monitoring_alarm" "backup_storage" {
  compartment_id        = var.compartment_ocid
  destinations          = [oci_ons_notification_topic.infrastructure.id]
  display_name          = "${var.name_prefix}-backup-storage"
  is_enabled            = true
  metric_compartment_id = var.compartment_ocid
  namespace             = "oci_objectstorage"
  pending_duration      = "PT5M"
  query                 = "StoredBytes[1h]{resourceName = \"${var.backup_bucket_name}\"}.max() > ${floor(var.max_object_storage_bytes * 0.8)}"
  resolution            = "1m"
  severity              = "CRITICAL"
  body                  = "Backup Object Storage exceeded 80% of its hard compartment quota."
  freeform_tags         = var.freeform_tags
}

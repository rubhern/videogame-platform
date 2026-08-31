resource "oci_identity_dynamic_group" "runtime" {
  compartment_id = var.tenancy_ocid
  description    = "Only compute instances in the private dev compartment"
  matching_rule  = "ALL {instance.compartment.id = '${var.compartment_ocid}'}"
  name           = replace("${var.name_prefix}-runtime", "-", "_")
  freeform_tags  = var.freeform_tags

  lifecycle {
    prevent_destroy = true
  }
}

resource "oci_identity_policy" "runtime" {
  compartment_id = var.tenancy_ocid
  description    = "Least-privilege runtime access to approved backups and secret bundles"
  name           = "${var.name_prefix}-runtime"
  freeform_tags  = var.freeform_tags
  statements = [
    "Allow dynamic-group ${oci_identity_dynamic_group.runtime.name} to read buckets in compartment ${var.compartment_name} where target.bucket.name = '${var.backup_bucket_name}'",
    "Allow dynamic-group ${oci_identity_dynamic_group.runtime.name} to manage objects in compartment ${var.compartment_name} where target.bucket.name = '${var.backup_bucket_name}'",
    "Allow dynamic-group ${oci_identity_dynamic_group.runtime.name} to read secret-bundles in compartment ${var.compartment_name} where target.vault.id = '${var.vault_ocid}'",
  ]

  lifecycle {
    prevent_destroy = true
  }
}

resource "oci_identity_compartment" "dev" {
  compartment_id = var.tenancy_ocid
  description    = "Private zero-recurring-cost development environment"
  name           = var.compartment_name
  freeform_tags  = var.freeform_tags

  lifecycle {
    prevent_destroy = true
  }
}

resource "oci_limits_quota" "compute_core" {
  compartment_id = var.tenancy_ocid
  description    = "Permit only the approved Ampere A1 OCPU envelope"
  name           = "${var.compartment_name}-compute-core"
  statements = [
    "zero compute-core quotas in compartment ${var.compartment_name}",
    "set compute-core quota standard-a1-core-count to ${var.max_a1_ocpus} in compartment ${var.compartment_name}",
  ]

  depends_on = [oci_identity_compartment.dev]
}

resource "oci_limits_quota" "compute_memory" {
  compartment_id = var.tenancy_ocid
  description    = "Permit only the approved Ampere A1 memory envelope"
  name           = "${var.compartment_name}-compute-memory"
  statements = [
    "zero compute-memory quotas in compartment ${var.compartment_name}",
    "set compute-memory quota standard-a1-memory-count to ${var.max_a1_memory_gb} in compartment ${var.compartment_name}",
  ]

  depends_on = [oci_identity_compartment.dev]
}

resource "oci_limits_quota" "prohibited_compute" {
  compartment_id = var.tenancy_ocid
  description    = "Deny paid fixed shapes, GPUs, pools, and automatic scaling"
  name           = "${var.compartment_name}-prohibited-compute"
  statements = [
    "zero compute quotas in compartment ${var.compartment_name}",
    "zero compute-gpu quotas in compartment ${var.compartment_name}",
    "zero compute-management quotas in compartment ${var.compartment_name}",
    "zero auto-scaling quotas in compartment ${var.compartment_name}",
  ]

  depends_on = [oci_identity_compartment.dev]
}

resource "oci_limits_quota" "storage" {
  compartment_id = var.tenancy_ocid
  description    = "Hard-cap block and object storage below the reviewed free allowances"
  name           = "${var.compartment_name}-storage"
  statements = [
    "set block-storage quota total-storage-gb to ${var.max_block_storage_gb} in compartment ${var.compartment_name}",
    "set object-storage quota storage-bytes to ${var.max_object_storage_bytes} in compartment ${var.compartment_name}",
  ]

  depends_on = [oci_identity_compartment.dev]
}

resource "oci_limits_quota" "vault" {
  compartment_id = var.tenancy_ocid
  description    = "Deny paid virtual private vaults"
  name           = "${var.compartment_name}-vault"
  statements = [
    "zero kms quota virtual-private-vault-count in compartment ${var.compartment_name}",
  ]

  depends_on = [oci_identity_compartment.dev]
}

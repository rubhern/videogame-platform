resource "oci_core_instance" "dev" {
  availability_domain = var.availability_domain
  compartment_id      = var.compartment_ocid
  display_name        = "${var.name_prefix}-a1"
  shape               = var.instance_shape
  freeform_tags       = var.freeform_tags

  shape_config {
    memory_in_gbs = var.instance_memory_gb
    ocpus         = var.instance_ocpus
  }

  create_vnic_details {
    assign_public_ip = true
    display_name     = "${var.name_prefix}-primary"
    hostname_label   = "dev"
    nsg_ids          = [var.network_security_group_ocid]
    subnet_id        = var.subnet_ocid
  }

  source_details {
    boot_volume_size_in_gbs = var.boot_volume_size_gb
    boot_volume_vpus_per_gb = 10
    source_id               = var.image_ocid
    source_type             = "image"
  }

  agent_config {
    are_all_plugins_disabled = false
    is_management_disabled   = false
    is_monitoring_disabled   = false
  }

  availability_config {
    is_live_migration_preferred = true
    recovery_action             = "STOP_INSTANCE"
  }

  instance_options {
    are_legacy_imds_endpoints_disabled = true
  }

  launch_options {
    is_consistent_volume_naming_enabled = true
    is_pv_encryption_in_transit_enabled = true
    network_type                        = "PARAVIRTUALIZED"
  }
}

resource "oci_core_volume_attachment" "data" {
  attachment_type                     = "paravirtualized"
  instance_id                         = oci_core_instance.dev.id
  is_pv_encryption_in_transit_enabled = true
  volume_id                           = var.data_volume_ocid
}

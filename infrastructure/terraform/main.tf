locals {
  name_prefix              = "videogame-platform-dev"
  max_block_storage_gb     = 150
  max_object_storage_bytes = 5000000000
  required_instance_shape  = "VM.Standard.A1.Flex"
  approved_freeform_tags   = { environment = "dev", project = "video-game-platform", recurring-cost = "zero-required" }
}

module "compartment" {
  source = "./modules/compartment"

  tenancy_ocid             = var.tenancy_ocid
  compartment_name         = var.compartment_name
  max_a1_ocpus             = var.instance_ocpus
  max_a1_memory_gb         = var.instance_memory_gb
  max_block_storage_gb     = local.max_block_storage_gb
  max_object_storage_bytes = local.max_object_storage_bytes
  freeform_tags            = local.approved_freeform_tags
}

data "oci_objectstorage_namespace" "tenancy" {
  compartment_id = var.tenancy_ocid
}

module "network" {
  source = "./modules/network"

  compartment_ocid = module.compartment.compartment_ocid
  name_prefix      = local.name_prefix
  freeform_tags    = local.approved_freeform_tags

  depends_on = [module.compartment]
}

module "storage" {
  source = "./modules/storage"

  compartment_ocid      = module.compartment.compartment_ocid
  namespace             = data.oci_objectstorage_namespace.tenancy.namespace
  name_prefix           = local.name_prefix
  availability_domain   = var.availability_domain
  data_volume_size_gb   = var.data_volume_size_gb
  backup_retention_days = var.backup_retention_days
  freeform_tags         = local.approved_freeform_tags

  depends_on = [module.compartment]
}

module "compute" {
  source = "./modules/compute"

  compartment_ocid            = module.compartment.compartment_ocid
  availability_domain         = var.availability_domain
  subnet_ocid                 = module.network.subnet_ocid
  network_security_group_ocid = module.network.network_security_group_ocid
  image_ocid                  = var.image_ocid
  instance_shape              = local.required_instance_shape
  instance_ocpus              = var.instance_ocpus
  instance_memory_gb          = var.instance_memory_gb
  boot_volume_size_gb         = var.boot_volume_size_gb
  data_volume_ocid            = module.storage.data_volume_ocid
  name_prefix                 = local.name_prefix
  freeform_tags               = local.approved_freeform_tags

  depends_on = [module.compartment]
}

module "iam" {
  source = "./modules/iam"

  tenancy_ocid       = var.tenancy_ocid
  compartment_ocid   = module.compartment.compartment_ocid
  compartment_name   = var.compartment_name
  backup_bucket_name = module.storage.backup_bucket_name
  vault_ocid         = module.storage.vault_ocid
  name_prefix        = local.name_prefix
  freeform_tags      = local.approved_freeform_tags
}

module "telemetry" {
  source = "./modules/telemetry"

  compartment_ocid         = module.compartment.compartment_ocid
  instance_ocid            = module.compute.instance_ocid
  backup_bucket_name       = module.storage.backup_bucket_name
  max_object_storage_bytes = local.max_object_storage_bytes
  name_prefix              = local.name_prefix
  freeform_tags            = local.approved_freeform_tags
}

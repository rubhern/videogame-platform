output "data_volume_ocid" {
  value = oci_core_volume.data.id
}

output "backup_bucket_name" {
  value = oci_objectstorage_bucket.backups.name
}

output "vault_ocid" {
  value = oci_kms_vault.runtime.id
}

output "vault_key_ocid" {
  value = oci_kms_key.runtime.id
}

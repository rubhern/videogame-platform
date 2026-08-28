output "compartment_ocid" {
  description = "Private dev compartment identifier."
  value       = module.compartment.compartment_ocid
  sensitive   = true
}

output "instance_ocid" {
  description = "A1 instance identifier for the later deployment issue."
  value       = module.compute.instance_ocid
  sensitive   = true
}

output "instance_private_ip" {
  description = "Private address used by the later owner-tailnet deployment."
  value       = module.compute.instance_private_ip
  sensitive   = true
}

output "data_volume_ocid" {
  description = "Durable data-volume identifier."
  value       = module.storage.data_volume_ocid
  sensitive   = true
}

output "backup_bucket_name" {
  description = "Private encrypted backup bucket name."
  value       = module.storage.backup_bucket_name
  sensitive   = true
}

output "vault_ocid" {
  description = "Vault identifier in which the owner creates secret payloads outside Terraform."
  value       = module.storage.vault_ocid
  sensitive   = true
}

output "vault_key_ocid" {
  description = "Software-protected Vault key identifier."
  value       = module.storage.vault_key_ocid
  sensitive   = true
}

output "notification_topic_ocid" {
  description = "Unsubscribed infrastructure alarm topic for protected endpoint setup."
  value       = module.telemetry.notification_topic_ocid
  sensitive   = true
}

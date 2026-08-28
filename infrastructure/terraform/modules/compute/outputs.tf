output "instance_ocid" {
  value = oci_core_instance.dev.id
}

output "instance_private_ip" {
  value = oci_core_instance.dev.private_ip
}

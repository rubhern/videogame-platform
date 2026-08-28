output "subnet_ocid" {
  value = oci_core_subnet.dev.id
}

output "network_security_group_ocid" {
  value = oci_core_network_security_group.instance.id
}

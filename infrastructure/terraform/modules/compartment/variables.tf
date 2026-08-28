variable "tenancy_ocid" { type = string }
variable "compartment_name" { type = string }
variable "max_a1_ocpus" { type = number }
variable "max_a1_memory_gb" { type = number }
variable "max_block_storage_gb" { type = number }
variable "max_object_storage_bytes" { type = number }
variable "freeform_tags" { type = map(string) }

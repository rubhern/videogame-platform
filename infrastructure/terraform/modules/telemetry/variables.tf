variable "compartment_ocid" { type = string }
variable "instance_ocid" { type = string }
variable "backup_bucket_name" { type = string }
variable "max_object_storage_bytes" { type = number }
variable "name_prefix" { type = string }
variable "freeform_tags" { type = map(string) }

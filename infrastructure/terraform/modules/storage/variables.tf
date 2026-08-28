variable "compartment_ocid" { type = string }
variable "namespace" { type = string }
variable "name_prefix" { type = string }
variable "availability_domain" { type = string }
variable "data_volume_size_gb" { type = number }
variable "backup_retention_days" { type = number }
variable "freeform_tags" { type = map(string) }

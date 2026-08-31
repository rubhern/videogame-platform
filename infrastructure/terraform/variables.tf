variable "tenancy_ocid" {
  description = "OCID of the tenancy that owns the private dev compartment."
  type        = string

  validation {
    condition     = can(regex("^ocid1\\.tenancy\\.", var.tenancy_ocid))
    error_message = "tenancy_ocid must be an OCI tenancy OCID."
  }
}

variable "region" {
  description = "OCI home-region identifier. The live preflight rejects any other region."
  type        = string

  validation {
    condition     = can(regex("^[a-z]+-[a-z]+-[0-9]+$", var.region))
    error_message = "region must be an OCI region identifier such as eu-madrid-1."
  }
}

variable "availability_domain" {
  description = "Home-region availability domain with verified A1 capacity."
  type        = string

  validation {
    condition     = length(trimspace(var.availability_domain)) > 0
    error_message = "availability_domain must be selected by the live preflight."
  }
}

variable "compartment_name" {
  description = "Globally unambiguous name for the private dev compartment."
  type        = string
  default     = "videogame-platform-dev"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{2,29}$", var.compartment_name))
    error_message = "compartment_name must be 3-30 lowercase letters, digits, or hyphens."
  }
}

variable "image_ocid" {
  description = "Current Always Free-eligible Ubuntu ARM64 image selected by preflight."
  type        = string

  validation {
    condition     = can(regex("^ocid1\\.image\\.", var.image_ocid))
    error_message = "image_ocid must be an OCI image OCID."
  }
}

variable "instance_ocpus" {
  description = "Hard-pinned A1 allocation."
  type        = number
  default     = 2

  validation {
    condition     = var.instance_ocpus == 2
    error_message = "Private dev is fixed at exactly 2 OCPU."
  }
}

variable "instance_memory_gb" {
  description = "Hard-pinned A1 memory allocation."
  type        = number
  default     = 12

  validation {
    condition     = var.instance_memory_gb == 12
    error_message = "Private dev is fixed at exactly 12 GB RAM."
  }
}

variable "boot_volume_size_gb" {
  description = "Fixed Always Free boot-volume allocation."
  type        = number
  default     = 50

  validation {
    condition     = var.boot_volume_size_gb == 50
    error_message = "The boot volume must remain exactly 50 GB."
  }
}

variable "data_volume_size_gb" {
  description = "Fixed durable data-volume allocation."
  type        = number
  default     = 100

  validation {
    condition     = var.data_volume_size_gb == 100
    error_message = "The data volume must remain exactly 100 GB."
  }
}

variable "backup_retention_days" {
  description = "Bounded Object Storage backup retention."
  type        = number
  default     = 14

  validation {
    condition     = var.backup_retention_days == 14
    error_message = "The reviewed backup retention is fixed at 14 days."
  }
}

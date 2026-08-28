resource "oci_core_vcn" "dev" {
  compartment_id = var.compartment_ocid
  cidr_blocks    = ["10.35.0.0/24"]
  display_name   = "${var.name_prefix}-vcn"
  dns_label      = "vgpdev"
  freeform_tags  = var.freeform_tags

  lifecycle {
    prevent_destroy = true
  }
}

resource "oci_core_internet_gateway" "egress" {
  compartment_id = var.compartment_ocid
  display_name   = "${var.name_prefix}-egress"
  enabled        = true
  vcn_id         = oci_core_vcn.dev.id
  freeform_tags  = var.freeform_tags
}

resource "oci_core_route_table" "egress" {
  compartment_id = var.compartment_ocid
  display_name   = "${var.name_prefix}-egress"
  vcn_id         = oci_core_vcn.dev.id
  freeform_tags  = var.freeform_tags

  route_rules {
    destination       = "0.0.0.0/0"
    destination_type  = "CIDR_BLOCK"
    network_entity_id = oci_core_internet_gateway.egress.id
  }
}

resource "oci_core_security_list" "egress_only" {
  compartment_id = var.compartment_ocid
  display_name   = "${var.name_prefix}-egress-only"
  vcn_id         = oci_core_vcn.dev.id
  freeform_tags  = var.freeform_tags

  egress_security_rules {
    destination      = "0.0.0.0/0"
    destination_type = "CIDR_BLOCK"
    protocol         = "all"
    stateless        = false
  }
}

resource "oci_core_subnet" "dev" {
  cidr_block                 = "10.35.0.0/28"
  compartment_id             = var.compartment_ocid
  display_name               = "${var.name_prefix}-subnet"
  dns_label                  = "host"
  prohibit_internet_ingress  = false
  prohibit_public_ip_on_vnic = false
  route_table_id             = oci_core_route_table.egress.id
  security_list_ids          = [oci_core_security_list.egress_only.id]
  vcn_id                     = oci_core_vcn.dev.id
  freeform_tags              = var.freeform_tags

  lifecycle {
    prevent_destroy = true
  }
}

resource "oci_core_network_security_group" "instance" {
  compartment_id = var.compartment_ocid
  display_name   = "${var.name_prefix}-instance"
  vcn_id         = oci_core_vcn.dev.id
  freeform_tags  = var.freeform_tags
}

resource "oci_core_network_security_group_security_rule" "egress" {
  network_security_group_id = oci_core_network_security_group.instance.id
  direction                 = "EGRESS"
  destination               = "0.0.0.0/0"
  destination_type          = "CIDR_BLOCK"
  protocol                  = "all"
  stateless                 = false
}

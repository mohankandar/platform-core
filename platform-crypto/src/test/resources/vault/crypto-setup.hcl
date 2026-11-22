# platform-crypto: the set-up step. Creates the customer's keys and rotates the wrapping key. No delete: destroying
# a customer's keys at customer exit needs the platform-crypto-offboard policy as well, given to that run only.
path "transit/keys/customer-*" {
  capabilities = ["create", "update", "read"]
}

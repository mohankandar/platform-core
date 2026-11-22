# platform-crypto: customer exit only (crypto destroy-customer-keys). Delete, nothing else, on the same paths as the
# set-up policy. Given together with platform-crypto-setup (which reads the key and allows its deletion,
# keys/<name>/config deletion_allowed) to the offboarding run only, never to the set-up step or the app.
path "transit/keys/customer-*" {
  capabilities = ["delete"]
}

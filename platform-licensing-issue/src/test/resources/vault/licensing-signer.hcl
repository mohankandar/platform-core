# licensing-signer: sign with the lic-* Transit keys and read their public keys. Nothing else.
path "transit/sign/lic-*" {
  capabilities = ["update"]
}

path "transit/keys/lic-*" {
  capabilities = ["read"]
}

# Never: export or back up a private key, even if another policy would allow it.
path "transit/export/*" {
  capabilities = ["deny"]
}

path "transit/backup/*" {
  capabilities = ["deny"]
}

# platform-crypto: the set-up step. Creates the customer's keys (transit/keys/<name>) and rotates the wrapping key
# (transit/keys/<name>/rotate), and nothing else (P044, SEC-2). "+" is one path segment: a key's settings
# (<name>/config: the lowest usable version, exportable, plaintext backup, deletion allowed), trimming old versions
# (<name>/trim), importing key material and deleting are not granted, so the set-up step can never make a key
# unreadable. (A trailing "*" would also cover <name>/config and <name>/trim.) Destroying keys is the offboarding
# policy's alone. The policy is for the Transit mount that holds the customer keys.
path "transit/keys/+" {
  capabilities = ["create", "update", "read"]
}
path "transit/keys/+/rotate" {
  capabilities = ["update"]
}

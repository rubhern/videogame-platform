#!/usr/bin/env python3
"""Verify the supported platform and content digests in the retained OCI archive."""
import hashlib
import json
import pathlib
import re
import sys
import tarfile


def inspect_archive(archive):
    with tarfile.open(archive, 'r') as image:
        def blob(descriptor):
            digest = descriptor.get('digest', '')
            if not re.fullmatch(r'sha256:[0-9a-f]{64}', digest):
                raise ValueError(f'Invalid OCI digest: {digest}')
            content = image.extractfile('blobs/sha256/' + digest[7:]).read()
            if hashlib.sha256(content).hexdigest() != digest[7:]:
                raise ValueError(f'OCI content digest mismatch: {digest}')
            if len(content) != descriptor.get('size'):
                raise ValueError(f'OCI content size mismatch: {digest}')
            return content

        index = json.load(image.extractfile('index.json'))
        descriptors = index.get('manifests', [])
        if len(descriptors) != 1:
            raise ValueError('Expected exactly one OCI archive descriptor')
        descriptor = descriptors[0]
        digest = descriptor['digest']
        manifest = json.loads(blob(descriptor))
        if 'manifests' in manifest:
            entries = manifest['manifests']
            if len(entries) != 1 or entries[0].get('platform') != {'os': 'linux', 'architecture': 'amd64'}:
                raise ValueError('Expected exactly one linux/amd64 platform manifest')
            manifest = json.loads(blob(entries[0]))
        config = json.loads(blob(manifest['config']))
        if (config.get('os'), config.get('architecture')) != ('linux', 'amd64'):
            raise ValueError('Expected linux/amd64 image configuration')
        for layer in manifest['layers']:
            blob(layer)
        return digest, manifest['config']['digest'], manifest


def main():
    digest, config_digest, manifest = inspect_archive(pathlib.Path(sys.argv[1]))
    evidence = pathlib.Path(sys.argv[2])
    (evidence / 'oci-manifest.json').write_text(json.dumps(manifest, indent=2, sort_keys=True) + '\n')
    (evidence / 'image-digest.txt').write_text(digest + '\n')
    (evidence / 'image-config-digest.txt').write_text(config_digest + '\n')
    (evidence / 'manifest-platforms.txt').write_text('linux/amd64\n')
    print(f'Verified OCI image {digest}: linux/amd64')


if __name__ == '__main__':
    main()

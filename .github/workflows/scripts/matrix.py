"""Generate the release matrix from the Stonecutter configuration."""

import json
import os
import re
import sys
from pathlib import Path


MATCH_PATTERN = re.compile(r'^\s*match\("([^"]+)"(?P<loaders>[^)]*)\)')
SECTION_PATTERN = re.compile(r'^\s*\["([^"]+)"\]\s*$')
PUBLISH_VERSIONS_PATTERN = re.compile(r'^\s*publish_versions\s*=\s*(".*")\s*$')


def load_targets(settings_path):
    targets = []
    for line in settings_path.read_text().splitlines():
        match = MATCH_PATTERN.match(line)
        if match is None:
            continue
        version = match.group(1)
        loaders = re.findall(r'"([^"]+)"', match.group('loaders'))
        targets.extend(f'{version}-{loader}' for loader in loaders)
    return targets


def load_publish_versions(properties_path):
    versions = {}
    section = None
    for line in properties_path.read_text().splitlines():
        section_match = SECTION_PATTERN.match(line)
        if section_match is not None:
            section = section_match.group(1)
            continue

        value_match = PUBLISH_VERSIONS_PATTERN.match(line)
        if section is not None and value_match is not None:
            versions[section] = json.loads(value_match.group(1))
    return versions


def main():
    repo_root = Path(__file__).resolve().parents[3]
    configured_targets = load_targets(repo_root / 'settings.gradle.kts')
    publish_versions = load_publish_versions(repo_root / 'stonecutter.properties.toml')

    requested_targets = [target for target in os.environ.get('TARGET_SUBPROJECT', '').split(',') if target]
    print('target_subprojects: {}'.format(requested_targets))

    unknown_targets = set(requested_targets) - set(configured_targets)
    if unknown_targets:
        print('Unexpected subprojects: {}'.format(sorted(unknown_targets)), file=sys.stderr)
        sys.exit(1)

    subprojects = [
        target for target in configured_targets
        if not requested_targets or target in requested_targets
    ]

    matrix_entries = []
    for subproject in subprojects:
        mc_version, mod_brand = subproject.split('-', maxsplit=1)
        if mc_version not in publish_versions:
            print('Missing publish_versions for {}'.format(mc_version), file=sys.stderr)
            sys.exit(1)
        matrix_entries.append({
            'subproject': subproject,
            'mod_brand': mod_brand,
            'mc_version': mc_version,
            'publish_versions': publish_versions[mc_version],
        })

    matrix = {'include': matrix_entries}

    with open(os.environ['GITHUB_OUTPUT'], 'w') as output:
        output.write('matrix={}\n'.format(json.dumps(matrix)))

    print('matrix:')
    print(json.dumps(matrix, indent=2))


if __name__ == '__main__':
    main()

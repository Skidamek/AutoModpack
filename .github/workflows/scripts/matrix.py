"""
A script to scan through the versions directory and collect all folder names as the subproject list,
then output a json as the github action include matrix
"""
__author__ = 'Fallen_Breath'
# edit Skidam

import json
import os
import sys


def main():
    target_subproject_env = os.environ.get('TARGET_SUBPROJECT', '')
    target_subprojects = list(filter(None, target_subproject_env.split(',') if target_subproject_env != '' else []))
    print('target_subprojects: {}'.format(target_subprojects))

    subprojects = []
    versions = os.listdir('versions')
    if len(target_subprojects) == 0:
        subprojects = versions
    else:
        for subproject in versions:
            if subproject in target_subprojects:
                subprojects.append(subproject)
                target_subprojects.remove(subproject)
        if len(target_subprojects) > 0:
            print('Unexpected subprojects: {}'.format(target_subprojects), file=sys.stderr)
            sys.exit(1)

    matrix_entries = []
    for subproject in subprojects:
        mc_version = subproject.split('-')[0]
        mod_brand = subproject.split('-')[1]
        matrix_entries.append({
            'subproject': subproject,
            'mod_brand': mod_brand,
            'mc_version': mc_version,
        })
        print('subproject: {}, mod_brand: {}, mc_version: {}'.format(subproject, mod_brand, mc_version))
    matrix = {'include': matrix_entries}

    print('matrix:')
    print(json.dumps(matrix, indent=2))

    with open(os.environ['GITHUB_OUTPUT'], 'w') as f:
        f.write('matrix={}\n'.format(json.dumps(matrix)))

    print('matrix:')
    print(json.dumps(matrix, indent=2))


if __name__ == '__main__':
    main()

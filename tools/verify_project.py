#!/usr/bin/env python3
import json, pathlib, re, sys, xml.etree.ElementTree as ET
root = pathlib.Path(__file__).resolve().parents[1]
errors=[]
config=json.loads((root/'app/src/main/assets/model_providers.json').read_text())
for p in [root/'app/src/main/AndroidManifest.xml', *(root/'app/src/main/res').rglob('*.xml')]:
    try: ET.parse(p)
    except Exception as e: errors.append(f'{p}: {e}')
java_root=root/'app/src/main/java'
for provider in config['providers']:
    cls=provider['class']
    source=java_root/pathlib.Path(*cls.split('.')).with_suffix('.java')
    if not source.exists(): errors.append(f'Missing provider source: {source}')
    ids=[m.get('id') for m in provider.get('config',{}).get('models',[])]
    if len(ids)!=len(set(ids)): errors.append(f'Duplicate model IDs in {provider["id"]}')
record=(java_root/'com/example/yapit/model/GenerationRecord.java').read_text()
for field in ['provider_id','model_id','model_name','model_version']:
    if field not in record: errors.append(f'GenerationRecord does not persist {field}')
worker=(java_root/'com/example/yapit/worker/GenerationWorker.java').read_text()
for forbidden in ['new AndroidSystemTtsProvider', 'new RestVoiceCloneProvider']:
    if forbidden in worker: errors.append('Worker is coupled to concrete provider: '+forbidden)
if errors:
    print('\n'.join(errors)); sys.exit(1)
print(f'OK: {len(config["providers"])} providers, {sum(len(p.get("config",{}).get("models",[])) for p in config["providers"])} configured models, XML/JSON and architecture checks passed.')

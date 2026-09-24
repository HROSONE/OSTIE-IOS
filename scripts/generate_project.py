#!/usr/bin/env python3
"""Generate a deterministic Xcode project using Python's standard library only."""
import hashlib, json, plistlib
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
IOS = ROOT / 'ios'
objects = {}
def uid(name): return hashlib.sha1(name.encode()).hexdigest()[:24].upper()
def obj(key, isa, **values):
    id = uid(key); objects[id] = {'isa': isa, **values}; return id
def plist(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(plistlib.dumps(content, sort_keys=True))
def dump(value, indent=0):
    pad='\t'*indent
    if isinstance(value, dict): return '{\n' + ''.join(pad+'\t'+json.dumps(k)+' = '+dump(v,indent+1)+';\n' for k,v in value.items()) + pad+'}'
    if isinstance(value, list): return '('+', '.join(dump(v,indent) for v in value)+')'
    if isinstance(value, int): return str(value)
    return json.dumps(value, ensure_ascii=False)

common_info={'CFBundleDevelopmentRegion':'pt_BR','CFBundleExecutable':'$(EXECUTABLE_NAME)','CFBundleIdentifier':'$(PRODUCT_BUNDLE_IDENTIFIER)','CFBundleInfoDictionaryVersion':'6.0','CFBundleName':'$(PRODUCT_NAME)','CFBundleShortVersionString':'$(MARKETING_VERSION)','CFBundleVersion':'$(CURRENT_PROJECT_VERSION)'}
app_info={**common_info,'CFBundleDisplayName':'OSTIE','CFBundlePackageType':'APPL','LSRequiresIPhoneOS':True,'UILaunchScreen':{},'UIRequiredDeviceCapabilities':['arm64'], 'UISupportedInterfaceOrientations':['UIInterfaceOrientationPortrait','UIInterfaceOrientationLandscapeLeft','UIInterfaceOrientationLandscapeRight'],'UIApplicationSceneManifest':{'UIApplicationSupportsMultipleScenes':False},'UIBackgroundModes':['audio'],'UIFileSharingEnabled':True,'LSSupportsOpeningDocumentsInPlace':True,'CFBundleURLTypes':[{'CFBundleURLSchemes':['ostie'],'CFBundleURLName':'com.hrosone.ostie.ios'}], 'LSApplicationQueriesSchemes':['whatsapp','shortcuts','maps','calshow'], 'NSMicrophoneUsageDescription':'Converse por voz com o OSTIE. O áudio é enviado ao Gemini enquanto o Live está ativo.','NSCameraUsageDescription':'Mostre imagens da câmera ao Gemini durante sua conversa com o OSTIE.','NSContactsUsageDescription':'Encontre os contatos que você pedir ao OSTIE.','NSCalendarsFullAccessUsageDescription':'Leia ou crie eventos na agenda quando você pedir ao OSTIE.'}
plist(IOS/'OSTIE/Info.plist',app_info)
plist(IOS/'BroadcastExtension/Info.plist',{**common_info,'CFBundleDisplayName':'Mostrar tela ao OSTIE','CFBundlePackageType':'XPC!','NSExtension':{'NSExtensionPointIdentifier':'com.apple.broadcast-services-upload','NSExtensionPrincipalClass':'$(PRODUCT_MODULE_NAME).SampleHandler','NSExtensionAttributes':{'RPBroadcastProcessMode':'RPBroadcastProcessModeSampleBuffer'}}})
plist(IOS/'ShareExtension/Info.plist',{**common_info,'CFBundleDisplayName':'OSTIE','CFBundlePackageType':'XPC!','NSExtension':{'NSExtensionPointIdentifier':'com.apple.share-services','NSExtensionPrincipalClass':'$(PRODUCT_MODULE_NAME).ShareViewController','NSExtensionAttributes':{'NSExtensionActivationRule':{'NSExtensionActivationSupportsText':True,'NSExtensionActivationSupportsWebURLWithMaxCount':5,'NSExtensionActivationSupportsImageWithMaxCount':5,'NSExtensionActivationSupportsMovieWithMaxCount':5,'NSExtensionActivationSupportsFileWithMaxCount':5}}}})
for folder in ['OSTIE','BroadcastExtension','ShareExtension']:
    plist(IOS/f'{folder}/{folder}.entitlements',{'com.apple.security.application-groups':['group.com.hrosone.ostie.ios']})
plist(IOS/'OSTIE/PrivacyInfo.xcprivacy',{'NSPrivacyTracking':False,'NSPrivacyTrackingDomains':[],'NSPrivacyCollectedDataTypes':[],'NSPrivacyAccessedAPITypes':[{'NSPrivacyAccessedAPIType':'NSPrivacyAccessedAPICategoryFileTimestamp','NSPrivacyAccessedAPITypeReasons':['C617.1']},{'NSPrivacyAccessedAPIType':'NSPrivacyAccessedAPICategorySystemBootTime','NSPrivacyAccessedAPITypeReasons':['35F9.1']}]})
assets=IOS/'OSTIE/Assets.xcassets'
(assets/'Contents.json').write_text(json.dumps({'info':{'author':'xcode','version':1}},indent=2)+'\n')
(assets/'Orb.imageset/Contents.json').write_text(json.dumps({'images':[{'filename':'orb.png','idiom':'universal'}],'info':{'author':'xcode','version':1}},indent=2)+'\n')

icon=assets/'AppIcon.appiconset';icon.mkdir(exist_ok=True)
(icon/'Contents.json').write_text(json.dumps({'images':[{'filename':'AppIcon.png','idiom':'universal','platform':'ios','size':'1024x1024'}],'info':{'author':'xcode','version':1}},indent=2)+'\n')
project_id=uid('project')
products=[]; groups=[]; targets=[]
configs_common={'SWIFT_VERSION':'5.0','IPHONEOS_DEPLOYMENT_TARGET':'17.0','SDKROOT':'iphoneos','TARGETED_DEVICE_FAMILY':'1,2','CLANG_ENABLE_MODULES':'YES','CLANG_ENABLE_OBJC_ARC':'YES','CODE_SIGN_STYLE':'Automatic','MARKETING_VERSION':'0.1.0','CURRENT_PROJECT_VERSION':'1','ENABLE_USER_SCRIPT_SANDBOXING':'YES','DEVELOPMENT_TEAM':''}
for name,folder,suffix,producttype in [('OSTIE','OSTIE','','application'),('OSTIEBroadcast','BroadcastExtension','.broadcast','app-extension'),('OSTIEShare','ShareExtension','.share','app-extension'),('OSTIETests','OSTIETests','.tests','bundle.unit-test')]:
    isapp=producttype=='application'; istest=producttype=='bundle.unit-test'
    ext='app' if isapp else 'xctest' if istest else 'appex'
    product=obj('product/'+name,'PBXFileReference',explicitFileType='wrapper.application' if isapp else 'wrapper.cfbundle',includeInIndex=0,path=name+'.'+ext,sourceTree='BUILT_PRODUCTS_DIR'); products.append(product)
    sources=sorted((IOS/folder).rglob('*.swift'))
    if not istest: sources += sorted((IOS/'Shared').glob('*.swift'))
    refs=[]; builds=[]; resources=[]
    for path in sources:
        rel=str(path.relative_to(IOS)); ref=obj(name+'/ref/'+rel,'PBXFileReference',lastKnownFileType='sourcecode.swift',path=rel,sourceTree='SOURCE_ROOT'); refs.append(ref)
        builds.append(obj(name+'/build/'+rel,'PBXBuildFile',fileRef=ref))
    if isapp:
        for rel,kind in [('OSTIE/Assets.xcassets','folder.assetcatalog'),('OSTIE/PrivacyInfo.xcprivacy','text.xml')]:
            ref=obj(name+'/ref/'+rel,'PBXFileReference',lastKnownFileType=kind,path=rel,sourceTree='SOURCE_ROOT');refs.append(ref);resources.append(obj(name+'/build/'+rel,'PBXBuildFile',fileRef=ref))
    groups.append(obj('group/'+name,'PBXGroup',children=refs,name=name,sourceTree='<group>'))
    phases=[obj('sources/'+name,'PBXSourcesBuildPhase',buildActionMask=2147483647,files=builds,runOnlyForDeploymentPostprocessing=0),obj('frameworks/'+name,'PBXFrameworksBuildPhase',buildActionMask=2147483647,files=[],runOnlyForDeploymentPostprocessing=0),obj('resources/'+name,'PBXResourcesBuildPhase',buildActionMask=2147483647,files=resources,runOnlyForDeploymentPostprocessing=0)]
    settings={**configs_common,'PRODUCT_NAME':'$(TARGET_NAME)','PRODUCT_BUNDLE_IDENTIFIER':'com.hrosone.ostie.ios'+suffix,'GENERATE_INFOPLIST_FILE':'YES' if istest else 'NO','SWIFT_EMIT_LOC_STRINGS':'YES','LD_RUNPATH_SEARCH_PATHS':['$(inherited)','@executable_path/Frameworks','@executable_path/../../Frameworks']}
    if isapp: settings['ASSETCATALOG_COMPILER_APPICON_NAME']='AppIcon'
    if istest: settings.update({'TEST_HOST':'$(BUILT_PRODUCTS_DIR)/OSTIE.app/$(BUNDLE_EXECUTABLE_FOLDER_PATH)/OSTIE','BUNDLE_LOADER':'$(TEST_HOST)'})
    else: settings.update({'INFOPLIST_FILE':folder+'/Info.plist','CODE_SIGN_ENTITLEMENTS':folder+'/'+folder+'.entitlements'})
    if not isapp and not istest: settings.update({'APPLICATION_EXTENSION_API_ONLY':'YES','SKIP_INSTALL':'YES'})
    configs=[]
    for config in ['Debug','Release']:
        specific={**settings,'SWIFT_OPTIMIZATION_LEVEL':'-Onone' if config=='Debug' else '-O','DEBUG_INFORMATION_FORMAT':'dwarf' if config=='Debug' else 'dwarf-with-dsym'}
        if config=='Debug': specific.update({'ENABLE_TESTABILITY':'YES','SWIFT_ACTIVE_COMPILATION_CONDITIONS':'DEBUG'})
        configs.append(obj(name+'/'+config,'XCBuildConfiguration',buildSettings=specific,name=config))
    configlist=obj('configs/'+name,'XCConfigurationList',buildConfigurations=configs,defaultConfigurationIsVisible=0,defaultConfigurationName='Release')
    targets.append(obj('target/'+name,'PBXNativeTarget',buildConfigurationList=configlist,buildPhases=phases,buildRules=[],dependencies=[],name=name,productName=name,productReference=product,productType='com.apple.product-type.'+producttype))
app=objects[uid('target/OSTIE')]
app['buildPhases'].insert(0,obj('icon-script','PBXShellScriptBuildPhase',buildActionMask=2147483647,files=[],inputPaths=['$(SRCROOT)/../scripts/prepare_assets.swift','$(SRCROOT)/OSTIE/Assets.xcassets/Orb.imageset/orb.png'],outputPaths=['$(SRCROOT)/OSTIE/Assets.xcassets/AppIcon.appiconset/AppIcon.png'],runOnlyForDeploymentPostprocessing=0,name='Prepare original OSTIE app icon',shellPath='/bin/sh',shellScript='set -e\nxcrun swift -module-cache-path "$DERIVED_FILE_DIR/SwiftModuleCache" "$SRCROOT/../scripts/prepare_assets.swift" "$SRCROOT/OSTIE/Assets.xcassets/Orb.imageset/orb.png" "$SRCROOT/OSTIE/Assets.xcassets/AppIcon.appiconset/AppIcon.png"\n'))
embed=[]
for name in ['OSTIEBroadcast','OSTIEShare','OSTIETests']:
    proxy=obj('proxy/'+name,'PBXContainerItemProxy',containerPortal=project_id,proxyType=1,remoteGlobalIDString=uid('target/OSTIE') if name=='OSTIETests' else uid('target/'+name),remoteInfo='OSTIE' if name=='OSTIETests' else name)
    dep=obj('dependency/'+name,'PBXTargetDependency',target=uid('target/OSTIE') if name=='OSTIETests' else uid('target/'+name),targetProxy=proxy)
    if name=='OSTIETests': objects[uid('target/OSTIETests')]['dependencies'].append(dep)
    else:
        app['dependencies'].append(dep)
        embed.append(obj('embed/'+name,'PBXBuildFile',fileRef=uid('product/'+name),settings={'ATTRIBUTES':['RemoveHeadersOnCopy']}))
app['buildPhases'].append(obj('embed','PBXCopyFilesBuildPhase',buildActionMask=2147483647,dstPath='',dstSubfolderSpec=13,files=embed,name='Embed App Extensions',runOnlyForDeploymentPostprocessing=0))
products_group=obj('products','PBXGroup',children=products,name='Products',sourceTree='<group>')
main_group=obj('main','PBXGroup',children=groups+[products_group],sourceTree='<group>')
pc=[]
for config in ['Debug','Release']: pc.append(obj('project/'+config,'XCBuildConfiguration',buildSettings=configs_common,name=config))
pcl=obj('project/configs','XCConfigurationList',buildConfigurations=pc,defaultConfigurationIsVisible=0,defaultConfigurationName='Release')
obj('project','PBXProject',attributes={'LastUpgradeCheck':'1600','BuildIndependentTargetsInParallel':'YES'},buildConfigurationList=pcl,compatibilityVersion='Xcode 14.0',developmentRegion='pt_BR',hasScannedForEncodings=0,knownRegions=['en','pt_BR','Base'],mainGroup=main_group,productRefGroup=products_group,projectDirPath='',projectRoot='',targets=targets)
proj=IOS/'OSTIE.xcodeproj';proj.mkdir(exist_ok=True)
(proj/'project.pbxproj').write_text('// !$*UTF8*$!\n'+dump({'archiveVersion':1,'classes':{},'objectVersion':56,'objects':objects,'rootObject':project_id})+'\n')
scheme=proj/'xcshareddata/xcschemes';scheme.mkdir(parents=True,exist_ok=True)
def ref(name,ext): return f'<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{uid("target/"+name)}" BuildableName="{name}.{ext}" BlueprintName="{name}" ReferencedContainer="container:OSTIE.xcodeproj"/>'
(scheme/'OSTIE.xcscheme').write_text(f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="1600" version="1.3">
<BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries><BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES">{ref('OSTIE','app')}</BuildActionEntry></BuildActionEntries></BuildAction>
<TestAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" shouldUseLaunchSchemeArgsEnv="YES"><Testables><TestableReference skipped="NO">{ref('OSTIETests','xctest')}</TestableReference></Testables></TestAction>
<LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugDocumentVersioning="YES" debugServiceExtension="internal" allowLocationSimulation="YES"><BuildableProductRunnable runnableDebuggingMode="0">{ref('OSTIE','app')}</BuildableProductRunnable></LaunchAction>
<ProfileAction buildConfiguration="Release" shouldUseLaunchSchemeArgsEnv="YES" savedToolIdentifier="" useCustomWorkingDirectory="NO" debugDocumentVersioning="YES"><BuildableProductRunnable runnableDebuggingMode="0">{ref('OSTIE','app')}</BuildableProductRunnable></ProfileAction>
<AnalyzeAction buildConfiguration="Debug"/><ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/>
</Scheme>''')
print('Generated ios/OSTIE.xcodeproj with app, broadcast, share, and test targets.')

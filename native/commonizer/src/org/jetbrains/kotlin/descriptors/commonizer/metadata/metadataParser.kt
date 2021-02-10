/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.descriptors.commonizer.metadata

import kotlinx.metadata.*
import kotlinx.metadata.klib.KlibModuleMetadata
import kotlinx.metadata.klib.fqName
import org.jetbrains.kotlin.descriptors.commonizer.CommonizerParameters
import org.jetbrains.kotlin.descriptors.commonizer.LeafTarget
import org.jetbrains.kotlin.descriptors.commonizer.ModulesProvider.ModuleInfo
import org.jetbrains.kotlin.descriptors.commonizer.TargetProvider
import org.jetbrains.kotlin.descriptors.commonizer.cir.CirEntityId
import org.jetbrains.kotlin.descriptors.commonizer.cir.CirName
import org.jetbrains.kotlin.descriptors.commonizer.cir.CirPackageName
import org.jetbrains.kotlin.descriptors.commonizer.cir.factory.*
import org.jetbrains.kotlin.descriptors.commonizer.mergedtree.*
import org.jetbrains.kotlin.descriptors.commonizer.metadata.utils.SerializedMetadataLibraryProvider
import org.jetbrains.kotlin.descriptors.commonizer.utils.*
import org.jetbrains.kotlin.storage.StorageManager

class CirTreeMergerV2(
    private val storageManager: StorageManager,
    private val classifiers: CirKnownClassifiers,
    private val parameters: CommonizerParameters
) {
    class CirTreeMergeResultV2(
        val root: CirRootNode,
        val missingModuleInfos: Map<LeafTarget, Collection<ModuleInfo>>
    )

    private val leafTargetsSize = parameters.targetProviders.size

    fun merge(): CirTreeMergeResultV2 {
        val result = processRoot()
        System.gc()
        return result
    }

    private fun processRoot(): CirTreeMergeResultV2 {
        val rootNode: CirRootNode = buildRootNode(storageManager, leafTargetsSize)

        // remember any exported forward declarations from common fragments of dependee modules
        parameters.dependeeModulesProvider?.loadModuleInfos()?.forEach(::processCInteropModuleAttributes)

        val commonModuleNames = parameters.getCommonModuleNames()
        val missingModuleInfosByTargets = mutableMapOf<LeafTarget, Collection<ModuleInfo>>()

        parameters.targetProviders.forEachIndexed { targetIndex, targetProvider ->
            val allModuleInfos = targetProvider.modulesProvider.loadModuleInfos()

            val (commonModuleInfos, missingModuleInfos) = allModuleInfos.partition { it.name in commonModuleNames }
            processTarget(rootNode, targetIndex, targetProvider, commonModuleInfos)

            missingModuleInfosByTargets[targetProvider.target] = missingModuleInfos

            parameters.progressLogger?.invoke("Loaded declarations for ${targetProvider.target.prettyName}")
            System.gc()
        }

        return CirTreeMergeResultV2(
            root = rootNode,
            missingModuleInfos = missingModuleInfosByTargets
        )
    }

    private fun processTarget(
        rootNode: CirRootNode,
        targetIndex: Int,
        targetProvider: TargetProvider,
        commonModuleInfos: Collection<ModuleInfo>
    ) {
        rootNode.targetDeclarations[targetIndex] = CirRootFactory.create(targetProvider.target)

        commonModuleInfos.forEach { moduleInfo ->
            val metadata = targetProvider.modulesProvider.loadModuleMetadata(moduleInfo.name)
            val module = KlibModuleMetadata.read(SerializedMetadataLibraryProvider(metadata))
            processModule(rootNode, targetIndex, moduleInfo, module)
        }
    }

    private fun processModule(
        rootNode: CirRootNode,
        targetIndex: Int,
        moduleInfo: ModuleInfo,
        module: KlibModuleMetadata
    ) {
        processCInteropModuleAttributes(moduleInfo)

        val moduleName: CirName = CirName.create(module.name)
        val moduleNode: CirModuleNode = rootNode.modules.getOrPut(moduleName) {
            buildModuleNode(storageManager, leafTargetsSize)
        }
        moduleNode.targetDeclarations[targetIndex] = CirModuleFactory.create(moduleName)

        val groupedFragments: Map<CirPackageName, Collection<KmModuleFragment>> = module.fragments.foldToMap { fragment ->
            fragment.fqName?.let(CirPackageName::create) ?: error("A fragment without FQ name in module $moduleName: $fragment")
        }

        groupedFragments.forEach { (packageName, fragments) ->
            processFragments(moduleNode, targetIndex, fragments, packageName)
        }
    }

    private fun processFragments(
        moduleNode: CirModuleNode,
        targetIndex: Int,
        fragments: Collection<KmModuleFragment>,
        packageName: CirPackageName
    ) {
        val packageNode: CirPackageNode = moduleNode.packages.getOrPut(packageName) {
            buildPackageNode(storageManager, leafTargetsSize)
        }
        packageNode.targetDeclarations[targetIndex] = CirPackageFactory.create(packageName)

        val classesToProcess = ClassesToProcess()
        fragments.forEach { fragment ->
            classesToProcess.addClasses(fragment.classes)

            fragment.pkg?.let { pkg ->
                pkg.properties.forEach { property -> processProperty(packageNode, targetIndex, property) }
                pkg.functions.forEach { function -> processFunction(packageNode, targetIndex, function) }
                pkg.typeAliases.forEach { typeAlias -> processTypeAlias(packageNode, targetIndex, typeAlias) }
            }
        }

        classesToProcess.forEachClassInScope(parentClassId = null) { classEntry ->
            processClass(packageNode, targetIndex, classEntry, classesToProcess)
        }
    }

    private fun processProperty(
        ownerNode: CirNodeWithMembers<*, *>,
        targetIndex: Int,
        property: KmProperty
    ) {
        if (property.isFakeOverride())
            return

        val maybeClassOwnerNode: CirClassNode? = ownerNode as? CirClassNode

        val approximationKey = PropertyApproximationKey(property)
        val propertyNode: CirPropertyNode = ownerNode.properties.getOrPut(approximationKey) {
            buildPropertyNode(storageManager, leafTargetsSize, classifiers, maybeClassOwnerNode?.commonDeclaration)
        }
        propertyNode.targetDeclarations[targetIndex] = CirPropertyFactory.create(
            name = approximationKey.name,
            source = property,
            containingClass = maybeClassOwnerNode?.targetDeclarations?.get(targetIndex)
        )
    }

    private fun processFunction(
        ownerNode: CirNodeWithMembers<*, *>,
        targetIndex: Int,
        function: KmFunction
    ) {
        if (function.isFakeOverride()
            || function.isKniBridgeFunction()
            || function.isTopLevelDeprecatedFunction(isTopLevel = ownerNode is CirClassNode)
        ) {
            return
        }

        val maybeClassOwnerNode: CirClassNode? = ownerNode as? CirClassNode

        val approximationKey = FunctionApproximationKey(function)
        val functionNode: CirFunctionNode = ownerNode.functions.getOrPut(approximationKey) {
            buildFunctionNode(storageManager, leafTargetsSize, classifiers, maybeClassOwnerNode?.commonDeclaration)
        }
        functionNode.targetDeclarations[targetIndex] = CirFunctionFactory.create(
            name = approximationKey.name,
            source = function,
            containingClass = maybeClassOwnerNode?.targetDeclarations?.get(targetIndex)
        )
    }

    private fun processClass(
        ownerNode: CirNodeWithMembers<*, *>,
        targetIndex: Int,
        classEntry: ClassesToProcess.ClassEntry,
        classesToProcess: ClassesToProcess
    ) {
        val (classId, clazz) = classEntry
        val className = classId.relativeNameSegments.last()

        val maybeClassOwnerNode: CirClassNode? = ownerNode as? CirClassNode
        val classNode: CirClassNode = ownerNode.classes.getOrPut(className) {
            buildClassNode(storageManager, leafTargetsSize, classifiers, maybeClassOwnerNode?.commonDeclaration, classId)
        }
        classNode.targetDeclarations[targetIndex] = CirClassFactory.create(className, clazz)

        if (!Flag.Class.IS_ENUM_ENTRY(clazz.flags)) {
            clazz.constructors.forEach { constructor -> processClassConstructor(classNode, targetIndex, constructor) }
        }

        clazz.properties.forEach { property -> processProperty(classNode, targetIndex, property) }
        clazz.functions.forEach { function -> processFunction(classNode, targetIndex, function) }

        classesToProcess.forEachClassInScope(parentClassId = classId) { nestedClassEntry ->
            processClass(classNode, targetIndex, nestedClassEntry, classesToProcess)
        }
    }

    private fun processClassConstructor(
        classNode: CirClassNode,
        targetIndex: Int,
        constructor: KmConstructor
    ) {
        val constructorNode: CirClassConstructorNode = classNode.constructors.getOrPut(ConstructorApproximationKey(constructor)) {
            buildClassConstructorNode(storageManager, leafTargetsSize, classifiers, classNode.commonDeclaration)
        }
        constructorNode.targetDeclarations[targetIndex] = CirClassConstructorFactory.create(
            source = constructor,
            containingClass = classNode.targetDeclarations[targetIndex]!!
        )
    }

    private fun processTypeAlias(
        packageNode: CirPackageNode,
        targetIndex: Int,
        typeAliasMetadata: KmTypeAlias
    ) {
        val typeAliasName = CirName.create(typeAliasMetadata.name)
        val typeAliasId = CirEntityId.create(packageNode.packageName, typeAliasName)

        val typeAliasNode: CirTypeAliasNode = packageNode.typeAliases.getOrPut(typeAliasName) {
            buildTypeAliasNode(storageManager, leafTargetsSize, classifiers, typeAliasId)
        }
        typeAliasNode.targetDeclarations[targetIndex] = CirTypeAliasFactory.create(typeAliasName, typeAliasMetadata)
    }

    private fun processCInteropModuleAttributes(moduleInfo: ModuleInfo) {
        val cInteropAttributes = moduleInfo.cInteropAttributes ?: return
        val exportForwardDeclarations = cInteropAttributes.exportForwardDeclarations.takeIf { it.isNotEmpty() } ?: return
        val mainPackageFqName = CirPackageName.create(cInteropAttributes.mainPackageFqName)

        exportForwardDeclarations.forEach { classFqName ->
            // Class has synthetic package FQ name (cnames/objcnames). Need to transfer it to the main package.
            val className = CirName.create(classFqName.substringAfterLast('.'))
            classifiers.forwardDeclarations.addExportedForwardDeclaration(CirEntityId.create(mainPackageFqName, className))
        }
    }
}

private class ClassesToProcess {
    data class ClassEntry(val classId: CirEntityId, val clazz: KmClass)

    private val groupedByParentClassId = HashMap<CirEntityId?, MutableCollection<ClassEntry>>() // j.u.HashMap permits null keys

    fun addClasses(classes: Collection<KmClass>) {
        classes.forEach { clazz ->
            val classId: CirEntityId = CirEntityId.create(clazz.name)
            val parentClassId: CirEntityId? = classId.getParentEntityId() // may be null for top-level classes

            groupedByParentClassId.getOrPut(parentClassId) { ArrayList() } += ClassEntry(classId, clazz)
        }
    }

    fun forEachClassInScope(parentClassId: CirEntityId?, block: (ClassEntry) -> Unit) {
        groupedByParentClassId[parentClassId]?.forEach { classEntry -> block(classEntry) }
    }
}

package com.oneops.transistor.service;

import com.oneops.cms.cm.domain.CmsCI;
import com.oneops.cms.cm.domain.CmsCIAttribute;
import com.oneops.cms.cm.domain.CmsCIRelation;
import com.oneops.cms.cm.domain.CmsCIRelationAttribute;
import com.oneops.cms.cm.service.CmsCmProcessor;
import com.oneops.cms.dj.domain.*;
import com.oneops.cms.dj.service.CmsCmRfcMrgProcessor;
import com.oneops.cms.dj.service.CmsRfcProcessor;
import com.oneops.cms.exceptions.DJException;
import com.oneops.cms.util.CmsConstants;
import com.oneops.cms.util.domain.AttrQueryCondition;
import com.oneops.transistor.exceptions.DesignExportException;
import com.oneops.transistor.export.domain.*;
import org.apache.log4j.Logger;

import java.util.*;
import java.util.stream.Collectors;

public class EnvironmentExportProcessor {
    private static final String USER_EXPORT = "export";
    private static Logger logger = Logger.getLogger(EnvironmentExportProcessor.class);
    private CmsCmProcessor cmProcessor;
    private DesignRfcProcessor designRfcProcessor;
    private CmsCmRfcMrgProcessor cmRfcMrgProcessor;
    private CmsRfcProcessor rfcProcessor;
    private TransUtil trUtil;
    private static final ExportFlavor FLAVOR = ExportFlavor.MANIFEST;
    private static final String BAD_ENV_ID_ERROR_MSG = "Environment does not exists with id=";
    private static final String OPEN_RELEASE_ERROR_MSG = "Design have open release. Please commit/discard before import.";
    private static final String BAD_TEMPLATE_ERROR_MSG = "Can not find template for pack: ";
    private static final String CANT_FIND_RELATION_ERROR_MSG = "Can not find $relationName relation for ci id=";
    private static final String CANT_FIND_PLATFORM_BY_NAME_ERROR_MSG = "Can not find platform with name: $toPlaform, used in links of platform $fromPlatform";
    private static final String CANT_FIND_COMPONENT_BY_NAME_ERROR_MSG = "Can not find component with name: $toComponent, used in depends of component $fromComponent";
    private static final String IMPORT_ERROR_PLAT_COMP = "Platform/Component - ";
    private static final String IMPORT_ERROR_PLAT_COMP_ATTACH = "Platform/Component/Attachment/Monitor - ";

    private static final String MGMT_REQUIRES_RELATION = "mgmt.Requires";
    private static final String MGMT_DEPENDS_ON_RELATION = "mgmt.catalog.DependsOn";
    private static final String ACCOUNT_CLOUD_CLASS = "account.Cloud";
    private static final String MGMT_PREFIX = "mgmt.";
    private static final String DESIGN_MONITOR_CLASS = "catalog.Monitor";

    private static final String OWNER_DESIGN = "design";
    private static final String OWNER_MANIFEST = "manifest";
    private static final String ATTR_SECURE = "secure";
    private static final String ATTR_VALUE = "value";
    private static final String ATTR_ENC_VALUE = "encrypted_value";

    private static final String DUMMY_ENCRYPTED_IMP_VALUE = "CHANGE ME!!!";
    private static final String ENCRYPTED_PREFIX = "::ENCRYPTED::";
    private static final String DUMMY_ENCRYPTED_EXP_VALUE = ENCRYPTED_PREFIX;

    public void setRfcProcessor(CmsRfcProcessor rfcProcessor) {
        this.rfcProcessor = rfcProcessor;
    }

    public void setTrUtil(TransUtil trUtil) {
        this.trUtil = trUtil;
    }


    public void setCmRfcMrgProcessor(CmsCmRfcMrgProcessor cmRfcMrgProcessor) {
        this.cmRfcMrgProcessor = cmRfcMrgProcessor;
    }

    public void setDesignRfcProcessor(DesignRfcProcessor designRfcProcessor) {
        this.designRfcProcessor = designRfcProcessor;
    }

    public void setCmProcessor(CmsCmProcessor cmProcessor) {
        this.cmProcessor = cmProcessor;
    }


    private DesignExportSimple exportDesign(long ciId, Long[] platformIds, String scope) {
        CmsCI ci = cmProcessor.getCiById(ciId);
        if (ci == null) {
            throw new DesignExportException(DesignExportException.CMS_NO_CI_WITH_GIVEN_ID_ERROR, BAD_ENV_ID_ERROR_MSG + ciId);
        }
        trUtil.verifyScope(ci, scope);
        //export platforms
        DesignExportSimple des = new DesignExportSimple();

        List<CmsCIRelation> composedOfs;
        if (platformIds == null || platformIds.length == 0) {
            //get the global vars
            List<CmsCIRelation> globalVarRels = cmProcessor.getToCIRelations(ciId, FLAVOR.getGlobalVarRelation(), FLAVOR.getGlobalVarClass());
            for (CmsCIRelation gvRel : globalVarRels) {
                des.addVariable(gvRel.getFromCi().getCiName(), checkVar4Export(gvRel.getFromCi(), false));
            }
            composedOfs = cmProcessor.getFromCIRelations(ciId, FLAVOR.getComposedOfRelation(), FLAVOR.getPlatformClass());
        } else {
            composedOfs = cmProcessor.getFromCIRelationsByToCiIds(ciId, FLAVOR.getComposedOfRelation(), null, Arrays.asList(platformIds));
        }

        for (CmsCIRelation composedOf : composedOfs) {
            CmsCI platform = composedOf.getToCi();

            //export platform ci
            PlatformExport pe = stripAndSimplify(PlatformExport.class, platform, FLAVOR.getOwner());


            //local vars
            List<CmsCIRelation> localVarRels = cmProcessor.getToCIRelations(platform.getCiId(), FLAVOR.getLocalVarRelation(), FLAVOR.getLocalVarClass());

            for (CmsCIRelation lvRel : localVarRels) {
                pe.addVariable(lvRel.getFromCi().getCiName(), checkVar4Export(lvRel.getFromCi(), true));
            }

            //components
            addComponentsToPlatformExport(platform, pe);


            addConsumesRelation(platform, pe);
            des.addPlatformExport(pe);
        }
        return des;
    }

    private void addConsumesRelation(CmsCI platform, PlatformExport pe) {
        List<CmsCIRelation> requiresRels = cmProcessor.getFromCIRelations(platform.getCiId(), FLAVOR.getConsumesRelation(), null);
        for (CmsCIRelation requires : requiresRels) {
            pe.addConsume(stripAndSimplify(ExportRelation.class, requires));
        }
    }


    private void addComponentsToPlatformExport(CmsCI platform, PlatformExport pe) {
        List<CmsCIRelation> requiresRels = cmProcessor.getFromCIRelations(platform.getCiId(), FLAVOR.getRequiresRelation(), null);
        for (CmsCIRelation requires : requiresRels) {
            CmsCI component = requires.getToCi();

            boolean isOptional = requires.getAttribute("constraint").getDjValue().startsWith("0.");    //always export optionals components or with attachments
            String template = requires.getAttribute("template").getDjValue();


            List<String> relationNames = Arrays.asList(FLAVOR.getEscortedRelation(), FLAVOR.getWatchedByRelation(), FLAVOR.getDependsOnRelation());

            Map<String, List<CmsCIRelation>> relationsMap = cmProcessor.getFromCIRelationsByMultiRelationNames(component.getCiId(),
                    relationNames, null);
            List<CmsCIRelation> attachmentRels = relationsMap.get(FLAVOR.getEscortedRelation());
            List<CmsCIRelation> watchedByRels = relationsMap.get(FLAVOR.getWatchedByRelation());
            List<CmsCIRelation> dependsOnRels = relationsMap.get(FLAVOR.getDependsOnRelation());
            if (attachmentRels == null)
                attachmentRels = Collections.emptyList();
            if (watchedByRels == null)
                watchedByRels = Collections.emptyList();
            if (dependsOnRels == null) {
                dependsOnRels = Collections.emptyList();
            }

            List<CmsCIRelation> flexes = dependsOnRels.stream().filter(relation -> relation.getAttribute("flex") != null && "true".equalsIgnoreCase(relation.getAttribute("flex").getDjValue())).collect(Collectors.toList());

            ComponentExport eCi = stripAndSimplify(ComponentExport.class, component, FLAVOR.getOwner(), false, ((attachmentRels.size() + watchedByRels.size() + flexes.size()) > 0 || isOptional));
            if (eCi != null) {
                eCi.setTemplate(template);
                for (CmsCIRelation attachmentRel : attachmentRels) {
                    eCi.addAttachment(stripAndSimplify(ExportCi.class, attachmentRel.getToCi(), FLAVOR.getOwner(), true, false));
                }
                for (CmsCIRelation watchedByRel : watchedByRels) {
                    ExportCi monitorCi = stripAndSimplify(ExportCi.class, watchedByRel.getToCi(), FLAVOR.getOwner(), isCustomMonitor(watchedByRel), false);
                    if (monitorCi != null) {
                        eCi.addMonitor(monitorCi);
                    }
                }
                for (CmsCIRelation scaling : flexes) {
                    eCi.addScaling(scaling.getToCi().getCiClassName(), scaling.getToCi().getCiName(), stripAndSimplify(ExportRelation.class, scaling));
                }
                if (isOptional || !isEmpty(eCi.getAttachments()) || !isEmpty(eCi.getMonitors()) || !isEmpty(eCi.getScaling())) {
                    pe.addComponent(eCi);
                }
            }
        }
    }

    private static boolean isEmpty(Map scaling) {
        return scaling == null || scaling.isEmpty();
    }

    private static boolean isEmpty(List list) {
        return list == null || list.size() == 0;
    }

    private boolean isCustomMonitor(CmsCIRelation watchedByRel) {
        CmsCI monitorCi = watchedByRel.getToCi();
        return (monitorCi.getAttribute(CmsConstants.MONITOR_CUSTOM_ATTR) != null &&
                "true".equalsIgnoreCase(monitorCi.getAttribute(CmsConstants.MONITOR_CUSTOM_ATTR).getDfValue()));
    }


    private <T extends ExportCi> T stripAndSimplify(Class<T> expType, CmsCI ci, String owner) {
        return stripAndSimplify(expType, ci, owner, true, false);
    }


    private <T extends ExportRelation> T stripAndSimplify(Class<T> expType, CmsCIRelation relation) {
        try {
            T exportCi = expType.newInstance();
            exportCi.setName(relation.getToCi().getCiName());
            exportCi.setType(relation.getRelationName());
            exportCi.setComments(relation.getComments());
            for (Map.Entry<String, CmsCIRelationAttribute> entry : relation.getAttributes().entrySet()) {
                String attrName = entry.getKey();
                String attrValue = checkEncrypted(relation.getAttribute(attrName).getDjValue());
                exportCi.addAttribute(attrName, attrValue);
            }
            return exportCi;
        } catch (InstantiationException | IllegalAccessException e) {
            e.printStackTrace();
            throw new DesignExportException(DesignExportException.TRANSISTOR_EXCEPTION, e.getMessage());
        }
    }

    private <T extends ExportCi> T stripAndSimplify(Class<T> expType, CmsCI ci, String owner, boolean force, boolean ignoreNoAttrs) {
        try {
            T exportCi = expType.newInstance();
            exportCi.setName(ci.getCiName());
            exportCi.setType(ci.getCiClassName());
            exportCi.setComments(ci.getComments());

            for (Map.Entry<String, CmsCIAttribute> entry : ci.getAttributes().entrySet()) {
                if (force || owner.equals(entry.getValue().getOwner())) {
                    String attrName = entry.getKey();
                    String attrValue = checkEncrypted(ci.getAttribute(attrName).getDjValue());
                    exportCi.addAttribute(attrName, attrValue);
                }
            }

            if ((exportCi.getAttributes() == null || exportCi.getAttributes().isEmpty()) && !force && !ignoreNoAttrs) {
                return null;
            }
            return exportCi;

        } catch (InstantiationException | IllegalAccessException e) {
            e.printStackTrace();
            throw new DesignExportException(DesignExportException.TRANSISTOR_EXCEPTION, e.getMessage());
        }
    }

    private String checkEncrypted(String value) {
        return (value != null && value.startsWith(ENCRYPTED_PREFIX)) ? ENCRYPTED_PREFIX : value;
    }

    EnvironmentExportSimple exportEnvironment(long envId, Long[] platformIds, String scope) {
        CmsCI env = cmProcessor.getCiById(envId);
        if (env == null) {
            throw new DesignExportException(DesignExportException.CMS_NO_CI_WITH_GIVEN_ID_ERROR, BAD_ENV_ID_ERROR_MSG + envId);
        }
        EnvironmentExportSimple exportSimple = new EnvironmentExportSimple();
        exportSimple.setEnvironment(stripAndSimplify(ExportCi.class, env, OWNER_MANIFEST));
        List<CmsCIRelation> clouds = cmProcessor.getFromCIRelations(envId, FLAVOR.getConsumesRelation(), ACCOUNT_CLOUD_CLASS);
        List<ExportRelation> consumes = new ArrayList<>();
        for (CmsCIRelation cloud : clouds) {
            ExportRelation exportRelation = stripAndSimplify(ExportRelation.class, cloud);
            exportRelation.setType(cloud.getToCi().getNsPath());
            consumes.add(exportRelation);
        }
        exportSimple.setConsumes(consumes);

        List<CmsCIRelation> relays = cmProcessor.getFromCIRelations(envId, "manifest.Delivers", "manifest.relay.email.Relay");
        List<ExportCi> delivers = new ArrayList<>();
        for (CmsCIRelation relay : relays) {
            delivers.add(stripAndSimplify(ExportCi.class, relay.getToCi(), FLAVOR.getOwner()));
        }
        exportSimple.setRelays(delivers);


        DesignExportSimple design = exportDesign(envId, platformIds, scope);
        exportSimple.setManifest(design);
        return exportSimple;
    }


    /**
     * IMPORT
     * @param environmentId
     * @param userId
     * @param scope
     * @param ees
     * @return
     */
    long importEnvironment(long environmentId, String userId, String scope, EnvironmentExportSimple ees) {
        CmsCI environment = cmProcessor.getCiById(environmentId);
        if (environment == null) {
            throw new DesignExportException(DesignExportException.CMS_NO_CI_WITH_GIVEN_ID_ERROR, BAD_ENV_ID_ERROR_MSG + environmentId);
        }

        String nsPath = environment.getNsPath() + "/" + environment.getCiName();

        List<CmsRelease> openReleases = rfcProcessor.getLatestRelease(nsPath, "open");
        if (openReleases.size() > 0) {
            throw new DesignExportException(DesignExportException.DJ_OPEN_RELEASE_FOR_NAMESPACE_ERROR, OPEN_RELEASE_ERROR_MSG);
        }

        List<ExportRelation> consumes = ees.getConsumes();
        Map<String, CmsCIRelation> map = cmProcessor.getFromCIRelations(environmentId, FLAVOR.getConsumesRelation(), ACCOUNT_CLOUD_CLASS).stream().collect(Collectors.toMap(x -> x.getToCi().getCiName(), x -> x));
        for (ExportRelation cloudRel : consumes) {
            CmsCIRelation rel = map.remove(cloudRel.getName());
            if (rel == null) { // need to add link to a cloud
                List<CmsCI> existingClouds = cmProcessor.getCiBy3(cloudRel.getType(), ACCOUNT_CLOUD_CLASS, cloudRel.getName());
                if (existingClouds != null && !existingClouds.isEmpty()) {
                    addRelation(FLAVOR.getConsumesRelation(), cloudRel.getType(), cloudRel.getAttributes(), environmentId, existingClouds.get(0).getCiId());
                } else {
                    logger.warn("There is no cloud:" + cloudRel.getName()); // this is error cloud doesn't exist 
                }
            } else {
                updateRelation(rel, cloudRel.getAttributes());
            }
        }
        for (CmsCIRelation existingRel : map.values()) {
            removeRelation(existingRel);
        }


        List<ExportCi> delivers = ees.getRelays();
        Map<String, CmsCIRelation> map1 = cmProcessor.getFromCIRelations(environmentId, "manifest.Delivers", "manifest.relay.email.Relay").stream().collect(Collectors.toMap(x -> x.getToCi().getCiName(), x -> x));
        for (ExportCi deliver : delivers) {
            CmsCIRelation del = map1.remove(deliver.getName());
            if (del == null) {
                addCi(nsPath, deliver);
            } else {
                updateCi(del.getToCi(), deliver);
            }
            for (CmsCIRelation existingRel : map1.values()) {
                removeRelation(existingRel);
                removeCi(existingRel.getToCi());
            }
        }


        return importDesignWithFlavor(environmentId, userId, scope, ees.getManifest(), nsPath);
    }


    private void addRelation(String name, String ns, Map<String, String> attributes, long fromCi, long toCi) {
        CmsRfcRelation rel = new CmsRfcRelation();
        rel.setNsPath(ns);
        rel.setRelationName(name);
        rel.setFromCiId(fromCi);
        rel.setToCiId(toCi);

        if (attributes != null) {
            for (Map.Entry<String, String> attr : attributes.entrySet()) {
                CmsRfcAttribute rfcAttr = new CmsRfcAttribute();
                if (attr.getValue() != null) {
                    rfcAttr.setAttributeName(attr.getKey());
                    rfcAttr.setNewValue(attr.getValue());
                    rfcAttr.setOwner(OWNER_MANIFEST);
                    rel.addAttribute(rfcAttr);
                }
            }
        }
        logger.info("adding relation:" + rel.getRelationName() + "@" + rel.getNsPath() + " " + rel.getFromCiId() + "->" + rel.getToCiId());
        cmRfcMrgProcessor.upsertRfcRelationNoCheck(rel, USER_EXPORT, null);
    }

    private void updateRelation(CmsCIRelation relation, Map<String, String> snapshotAttributes) {
        CmsRfcRelation rel = new CmsRfcRelation();
        rel.setNsPath(relation.getNsPath());
        rel.setToCiId(relation.getToCiId());
        rel.setFromCiId(relation.getFromCiId());

        Map<String, CmsCIRelationAttribute> existingAttributes = relation.getAttributes();
        relation.setRelationId(relation.getRelationId());
        for (String key : snapshotAttributes.keySet()) {
            CmsCIRelationAttribute ciAttribute = existingAttributes.remove(key);
            String value = snapshotAttributes.get(key);
            if (ciAttribute == null || (ciAttribute.getDfValue() == null && value != null) || (ciAttribute.getDfValue() != null && !ciAttribute.getDfValue().equals(value))) {
                rel.addAttribute(getAttributeRfc(key, value));
            }
        }
        if (!rel.getAttributes().isEmpty()) {
            logger.info("Updating relation:" + relation.getRelationName() + "@" + relation.getNsPath());
            cmRfcMrgProcessor.upsertRelationRfc(rel, "import");
        } else {
            logger.info("Nothing to update in relation:" + relation.getRelationName() + "@" + relation.getNsPath());
        }
    }


    private CmsRfcCI addCi(String ns, ExportCi eci) {
        CmsRfcCI rfc = new CmsRfcCI();
        rfc.setCiName(eci.getName());
        rfc.setCiClassName(eci.getType());
        rfc.setNsPath(ns);
        processAttributes(eci, rfc);
        logger.info("adding ci:" + rfc.getCiName() + "@" + rfc.getNsPath());
        return cmRfcMrgProcessor.upsertCiRfc(rfc, USER_EXPORT);
    }

    private void removeCi(CmsCI ci) {
        logger.info("removing ci:" + ci.getCiName() + "@" + ci.getNsPath());
        cmRfcMrgProcessor.requestCiDelete(ci.getCiId(), USER_EXPORT);
    }

    private void processAttributes(ExportCi exportRelation, CmsRfcContainer rel) {
        if (exportRelation.getAttributes() != null) {
            for (Map.Entry<String, String> attr : exportRelation.getAttributes().entrySet()) {
                CmsRfcAttribute rfcAttr = new CmsRfcAttribute();
                if (attr.getValue() != null) {
                    rfcAttr.setAttributeName(attr.getKey());
                    rfcAttr.setNewValue(attr.getValue());
                    rel.addAttribute(rfcAttr);
                }
            }
        }
    }


    private void updateCi(CmsCI ci, ExportCi eci) {
        Map<String, CmsCIAttribute> existingAttributes = ci.getAttributes();
        Map<String, String> snapshotAttributes = eci.getAttributes();
        CmsRfcCI rfc = new CmsRfcCI();
        rfc.setCiName(eci.getName());
        rfc.setCiClassName(eci.getType());
        rfc.setNsPath(ci.getNsPath());
        rfc.setCiId(ci.getCiId());
        for (String key : snapshotAttributes.keySet()) {
            CmsCIAttribute ciAttribute = existingAttributes.remove(key);
            String value = snapshotAttributes.get(key);

            if (ciAttribute == null || (ciAttribute.getDfValue() == null && value != null) || (ciAttribute.getDfValue() != null && !ciAttribute.getDfValue().equals(value))) {
                CmsRfcAttribute rfcAttr = getAttributeRfc(key, value);
                rfc.addAttribute(rfcAttr);
            }
        }
        if (!rfc.getAttributes().isEmpty()) {
            logger.info("Updating:" + ci.getCiName() + "@" + ci.getNsPath());
            cmRfcMrgProcessor.upsertRfcCINoChecks(rfc, USER_EXPORT, null);

        } else {
            logger.info("no need to update:" + ci.getCiName() + "@" + ci.getNsPath());
        }
    }

    private CmsRfcAttribute getAttributeRfc(String key, String value) {
        CmsRfcAttribute rfcAttr = new CmsRfcAttribute();
        rfcAttr.setAttributeName(key);
        rfcAttr.setNewValue(value);
        rfcAttr.setOwner(OWNER_MANIFEST);
        return rfcAttr;
    }


    private void removeRelation(CmsCIRelation existingRel) {
        CmsRfcRelation newRfc = new CmsRfcRelation();
        newRfc.setCiRelationId(existingRel.getCiRelationId());
        newRfc.setFromCiId(existingRel.getFromCiId());
        newRfc.setToCiId(existingRel.getToCiId());
        newRfc.setNsId(existingRel.getNsId());
        newRfc.setNsPath(existingRel.getNsPath());
        newRfc.setRelationGoid(existingRel.getRelationGoid());
        newRfc.setRelationId(existingRel.getRelationId());
        newRfc.setRelationName(existingRel.getRelationName());
        newRfc.setComments("deleting");
        newRfc.setRfcAction("delete");
        newRfc.setExecOrder(0);
        newRfc.setCreatedBy(USER_EXPORT);
        newRfc.setUpdatedBy(USER_EXPORT);
        logger.info("Removing relation:" + newRfc.getRelationName() + "@" + newRfc.getNsPath());
        rfcProcessor.createRfcRelation(newRfc, USER_EXPORT);
    }


    

    private long importDesignWithFlavor(long ciId, String userId, String scope, DesignExportSimple des, String designNsPath) {
        if (des.getVariables() != null && !des.getVariables().isEmpty()) {
            importGlobalVars(ciId, designNsPath, des.getVariables(), userId);
        }
        for (PlatformExport platformExp : des.getPlatforms()) {

            CmsRfcCI platformRfc = newFromExportCiWithMdAttrs(platformExp, designNsPath, designNsPath, new HashSet<>(Collections.singletonList("description")));
            List<CmsRfcCI> existingPlatRfcs = cmRfcMrgProcessor.getDfDjCi(designNsPath, platformRfc.getCiClassName(), platformRfc.getCiName(), null);
            CmsRfcCI designPlatform;
            if (existingPlatRfcs.size() > 0) {
                CmsRfcCI existingPlat = existingPlatRfcs.get(0);
                boolean needUpdate = false;
                if (platformExp.getAttributes() != null) {
                    if (platformExp.getAttributes().containsKey("major_version")
                            && !existingPlat.getAttribute("major_version").getNewValue().equals(platformExp.getAttributes().get("major_version"))) {

                        existingPlat.getAttribute("major_version").setNewValue(platformExp.getAttributes().get("major_version"));
                        needUpdate = true;
                    }
                    if (platformExp.getAttributes().containsKey("description")
                            && !existingPlat.getAttribute("description").getNewValue().equals(platformExp.getAttributes().get("description"))) {

                        existingPlat.getAttribute("description").setNewValue(platformExp.getAttributes().get("description"));
                        needUpdate = true;
                    }

                }
                if (needUpdate) {
                    designPlatform = cmRfcMrgProcessor.upsertCiRfc(existingPlat, userId);
                } else {
                    designPlatform = existingPlat;
                }
            } else {
                if (platformRfc.getAttribute("description").getNewValue() == null) {
                    platformRfc.getAttribute("description").setNewValue("");
                }
                designPlatform = designRfcProcessor.generatePlatFromTmpl(platformRfc, ciId, userId, scope);
            }
            String platNsPath = designPlatform.getNsPath() + "/_design/" + designPlatform.getCiName();
            String packNsPath = getPackNsPath(designPlatform);
            //local vars
            if (platformExp.getVariables() != null) {
                importLocalVars(designPlatform.getCiId(), platNsPath, designNsPath, platformExp.getVariables(), userId);
            }
            if (platformExp.getComponents() != null) {
                Set<Long> componentIds = new HashSet<>();
                for (ComponentExport componentExp : platformExp.getComponents()) {
                    componentIds.add(importComponent(designPlatform, componentExp, platNsPath, designNsPath, packNsPath, userId));
                }
                importDepends(platformExp.getComponents(), platNsPath, designNsPath, userId);
                //if its existing platform - process absolete components
                if (existingPlatRfcs.size() > 0) {
                    procesObsoleteOptionalComponents(designPlatform.getCiId(), componentIds, userId);
                }
            }
        }

        //process LinkTos
        importLinksTos(des, designNsPath, userId);

        CmsRelease release = cmRfcMrgProcessor.getReleaseByNameSpace(designNsPath);
        if (release != null) {
            return release.getReleaseId();
        } else {
            return 0;
        }
    }

    private void procesObsoleteOptionalComponents(long platformId, Set<Long> importedCiIds, String userId) {
        List<CmsCIRelation> requiresRels = cmProcessor.getFromCIRelations(platformId, FLAVOR.getRequiresRelation(), null);
        for (CmsCIRelation requires : requiresRels) {
            if (requires.getAttribute("constraint").getDjValue().startsWith("0.")) {
                if (!importedCiIds.contains(requires.getToCiId())) {
                    //this is absolete optional component that does not exists in export - remove from design
                    cmRfcMrgProcessor.requestCiDelete(requires.getToCiId(), userId);
                }
            }
        }
    }

    private void importLinksTos(DesignExportSimple des, String designNsPath, String userId) {
        Map<String, CmsRfcCI> platforms = new HashMap<>();
        List<CmsRfcCI> existingPlatRfcs = cmRfcMrgProcessor.getDfDjCi(designNsPath, FLAVOR.getPlatformClass(), null, "dj");
        for (CmsRfcCI platformRfc : existingPlatRfcs) {
            platforms.put(platformRfc.getCiName(), platformRfc);
        }
        for (PlatformExport platformExp : des.getPlatforms()) {
            if (platformExp.getLinks() != null && !platformExp.getLinks().isEmpty()) {
                for (String toPlatformName : platformExp.getLinks()) {
                    CmsRfcCI toPlatform = platforms.get(toPlatformName);
                    if (toPlatform == null) {
                        String errorMsg = CANT_FIND_PLATFORM_BY_NAME_ERROR_MSG.replace("$toPlatform", toPlatformName).replace("$fromPlatform", platformExp.getName());
                        throw new DesignExportException(DesignExportException.CMS_NO_CI_WITH_GIVEN_ID_ERROR, errorMsg);
                    }
                    CmsRfcCI fromPlatform = platforms.get(platformExp.getName());
                    CmsRfcRelation LinksTo = trUtil.bootstrapRelationRfc(fromPlatform.getCiId(), toPlatform.getCiId(), FLAVOR.getLinksToRelation(), designNsPath, designNsPath, null);
                    upsertRelRfc(LinksTo, fromPlatform, toPlatform, 0, userId);
                }
            }
        }
    }

    private void importDepends(List<ComponentExport> componentExports, String platformNsPath, String designNsPath, String userId) {
        for (ComponentExport ce : componentExports) {
            if (ce.getDepends() != null && !ce.getDepends().isEmpty()) {
                Map<String, CmsRfcCI> components = new HashMap<>();
                List<CmsRfcCI> existingComponentRfcs = cmRfcMrgProcessor.getDfDjCi(platformNsPath, ce.getType(), null, "dj");
                for (CmsRfcCI componentRfc : existingComponentRfcs) {
                    components.put(componentRfc.getCiName(), componentRfc);
                }
                for (String toComponentName : ce.getDepends()) {
                    CmsRfcCI toComponent = components.get(toComponentName);
                    if (toComponent == null) {
                        String errorMsg = CANT_FIND_COMPONENT_BY_NAME_ERROR_MSG.replace("$toPlatform", toComponentName).replace("$fromPlatform", ce.getName());
                        throw new DesignExportException(DesignExportException.CMS_NO_CI_WITH_GIVEN_ID_ERROR, errorMsg);
                    }
                    CmsRfcCI fromComponent = components.get(ce.getName());
                    if (fromComponent.getCiClassName().equals(toComponent.getCiClassName())) {
                        Map<String, CmsCIRelationAttribute> attrs = new HashMap<>();
                        CmsCIRelationAttribute attr = new CmsCIRelationAttribute();
                        attr.setAttributeName("source");
                        attr.setDjValue("user");
                        attrs.put(attr.getAttributeName(), attr);
                        CmsRfcRelation dependsOn = trUtil.bootstrapRelationRfcWithAttrs(fromComponent.getCiId(), toComponent.getCiId(), FLAVOR.getDependsOnRelation(), platformNsPath, designNsPath, attrs);
                        upsertRelRfc(dependsOn, fromComponent, toComponent, 0, userId);
                    }
                }
            }
        }
    }


    private void importGlobalVars(long assemblyId, String designNsPath, Map<String, String> globalVars, String userId) {

        for (Map.Entry<String, String> var : globalVars.entrySet()) {
            List<CmsRfcCI> existingVars = cmRfcMrgProcessor.getDfDjCiNakedLower(designNsPath, FLAVOR.getGlobalVarClass(), var.getKey(), null);
            Set<String> attrsToBootstrap = new HashSet<>();
            CmsRfcCI varBaseRfc;
            if (var.getValue().startsWith(ENCRYPTED_PREFIX)) {
                attrsToBootstrap.add(ATTR_SECURE);
                attrsToBootstrap.add(ATTR_ENC_VALUE);
                varBaseRfc = trUtil.bootstrapRfc(var.getKey(), FLAVOR.getGlobalVarClass(), designNsPath, designNsPath, attrsToBootstrap);
                varBaseRfc.getAttribute(ATTR_SECURE).setNewValue("true");
                varBaseRfc.getAttribute(ATTR_ENC_VALUE).setNewValue(parseEncryptedImportValue(var.getValue()));
            } else {
                attrsToBootstrap.add(ATTR_VALUE);
                varBaseRfc = trUtil.bootstrapRfc(var.getKey(), FLAVOR.getGlobalVarClass(), designNsPath, designNsPath, attrsToBootstrap);
                varBaseRfc.getAttribute(ATTR_VALUE).setNewValue(var.getValue());
            }

            if (existingVars.isEmpty()) {
                logger.info("Adding global variable:" + varBaseRfc.getCiName());
                CmsRfcCI varRfc = cmRfcMrgProcessor.upsertCiRfc(varBaseRfc, userId);
                CmsRfcRelation valueForRel = trUtil.bootstrapRelationRfc(varRfc.getCiId(), assemblyId, FLAVOR.getGlobalVarRelation(), designNsPath, designNsPath, null);
                valueForRel.setFromRfcId(varRfc.getRfcId());
                cmRfcMrgProcessor.upsertRelationRfc(valueForRel, userId);
            } else {
                CmsRfcCI existingVar = existingVars.get(0);
                varBaseRfc.setCiId(existingVar.getCiId());
                varBaseRfc.setRfcId(existingVar.getRfcId());
                cmRfcMrgProcessor.upsertCiRfc(varBaseRfc, userId);
            }
        }
    }

    private String parseEncryptedImportValue(String encValue) {
        String value = encValue.substring(ENCRYPTED_PREFIX.length());
        if (value.length() == 0) {
            value = DUMMY_ENCRYPTED_IMP_VALUE;
        }
        return value;
    }


    private void importLocalVars(long platformId, String platformNsPath, String releaseNsPath, Map<String, String> localVars, String userId) {

        for (Map.Entry<String, String> var : localVars.entrySet()) {
            List<CmsRfcCI> existingVars = cmRfcMrgProcessor.getDfDjCiNakedLower(platformNsPath, FLAVOR.getLocalVarClass(), var.getKey(), null);
            Set<String> attrsToBootstrap = new HashSet<String>();
            CmsRfcCI varBaseRfc = null;
            String varValue = null;
            if (var.getValue() == null) {
                varValue = "";
            } else {
                varValue = var.getValue();
            }

            if (varValue.startsWith(ENCRYPTED_PREFIX)) {
                attrsToBootstrap.add(ATTR_SECURE);
                attrsToBootstrap.add(ATTR_ENC_VALUE);
                varBaseRfc = trUtil.bootstrapRfc(var.getKey(), FLAVOR.getLocalVarClass(), platformNsPath, releaseNsPath, attrsToBootstrap);
                varBaseRfc.getAttribute(ATTR_SECURE).setNewValue("true");
                varBaseRfc.getAttribute(ATTR_ENC_VALUE).setNewValue(parseEncryptedImportValue(varValue));
                varBaseRfc.getAttribute(ATTR_ENC_VALUE).setOwner(OWNER_DESIGN);

            } else {
                attrsToBootstrap.add(ATTR_VALUE);
                varBaseRfc = trUtil.bootstrapRfc(var.getKey(), FLAVOR.getLocalVarClass(), platformNsPath, releaseNsPath, attrsToBootstrap);
                varBaseRfc.getAttribute(ATTR_VALUE).setNewValue(varValue);
                varBaseRfc.getAttribute(ATTR_VALUE).setOwner(OWNER_DESIGN);
            }

            if (existingVars.isEmpty()) {
                CmsRfcCI varRfc = cmRfcMrgProcessor.upsertCiRfc(varBaseRfc, userId);
                CmsRfcRelation valueForRel = trUtil.bootstrapRelationRfc(varRfc.getCiId(), platformId, FLAVOR.getLocalVarRelation(), platformNsPath, releaseNsPath, null);
                valueForRel.setFromRfcId(varRfc.getRfcId());
                cmRfcMrgProcessor.upsertRelationRfc(valueForRel, userId);
            } else {
                CmsRfcCI existingVar = existingVars.get(0);
                varBaseRfc.setCiId(existingVar.getCiId());
                varBaseRfc.setRfcId(existingVar.getRfcId());
                cmRfcMrgProcessor.upsertCiRfc(varBaseRfc, userId);
            }
        }
    }


    private long importComponent(CmsRfcCI designPlatform, ComponentExport compExpCi, String platNsPath, String releaseNsPath, String packNsPath, String userId) {
        List<CmsRfcCI> existingComponent = cmRfcMrgProcessor.getDfDjCiNakedLower(platNsPath, compExpCi.getType(), compExpCi.getName(), null);
        CmsRfcCI componentRfc = null;
        try {
            if (existingComponent.size() > 0) {
                CmsRfcCI existingRfc = existingComponent.get(0);
                CmsRfcCI component = newFromExportCi(compExpCi);
                component.setNsPath(platNsPath);
                component.setRfcId(existingRfc.getRfcId());
                component.setCiId(existingRfc.getCiId());
                component.setReleaseNsPath(releaseNsPath);
                componentRfc = cmRfcMrgProcessor.upsertCiRfc(component, userId);
            } else {
                //this is optional component lets find template
                List<CmsCI> mgmtComponents = cmProcessor.getCiBy3(packNsPath, MGMT_PREFIX + compExpCi.getType(), compExpCi.getTemplate());
                if (mgmtComponents.isEmpty()) {
                    //can not find template - abort
                    throw new DesignExportException(DesignExportException.CMS_CANT_FIGURE_OUT_TEMPLATE_FOR_MANIFEST_ERROR, BAD_TEMPLATE_ERROR_MSG + packNsPath + ";" + compExpCi.getType() + ";" + compExpCi.getTemplate());
                }

                CmsCI template = mgmtComponents.get(0);
                CmsRfcCI component = designRfcProcessor.popRfcCiFromTemplate(template, "catalog", platNsPath, releaseNsPath);
                applyExportCiToTemplateRfc(compExpCi, component);
                componentRfc = cmRfcMrgProcessor.upsertCiRfc(component, userId);
                createRelationFromMgmt(designPlatform, template, componentRfc, MGMT_REQUIRES_RELATION, userId);
                processMgmtDependsOnRels(designPlatform, template, componentRfc, userId);
            }
        } catch (DJException dje) {
            //missing required attributes
            throw new DesignExportException(dje.getErrorCode(), IMPORT_ERROR_PLAT_COMP
                    + designPlatform.getCiName()
                    + "/" + compExpCi.getName() + ":" + dje.getMessage());
        }

        if (compExpCi.getAttachments() != null) {
            for (ExportCi attachmentExp : compExpCi.getAttachments()) {
                try {
                    importAttachements(componentRfc, attachmentExp, releaseNsPath, userId);
                } catch (DJException dje) {
                    throw new DesignExportException(dje.getErrorCode(), getComponentImportError(designPlatform, compExpCi, attachmentExp, dje.getMessage()));
                }
            }
        }
        if (compExpCi.getMonitors() != null) {
            for (ExportCi monitorExp : compExpCi.getMonitors()) {
                try {
                    importMonitor(designPlatform, componentRfc, monitorExp, platNsPath, releaseNsPath, packNsPath, userId);
                } catch (DJException dje) {
                    throw new DesignExportException(dje.getErrorCode(), getComponentImportError(designPlatform, compExpCi, monitorExp, dje.getMessage()));
                }
            }
        }
        return componentRfc.getCiId();
    }

    private String getComponentImportError(CmsRfcCI designPlatform, ComponentExport compExpCi, ExportCi ciExp, String message) {
        return IMPORT_ERROR_PLAT_COMP_ATTACH
                + designPlatform.getCiName()
                + "/" + compExpCi.getName()
                + "/" + ciExp.getName() + ":" + message;
    }

    private void importAttachements(CmsRfcCI componentRfc, ExportCi attachmentExp, String releaseNsPath, String userId) {
        List<CmsRfcCI> existingAttachments = cmRfcMrgProcessor.getDfDjCiNakedLower(componentRfc.getNsPath(), FLAVOR.getAttachmentClass(), attachmentExp.getName(), null);
        if (!existingAttachments.isEmpty()) {
            CmsRfcCI existingRfc = existingAttachments.get(0);
            mergeRfcWithExportCi(existingRfc, attachmentExp, releaseNsPath, userId);
        } else {
            createRfcAndRelationFromExportCi(componentRfc, attachmentExp, FLAVOR.getEscortedRelation(), releaseNsPath, userId);
        }
    }

    private CmsRfcCI mergeRfcWithExportCi(CmsRfcCI existingRfc, ExportCi exportCi, String releaseNsPath, String userId) {
        CmsRfcCI attachment = newFromExportCi(exportCi);
        attachment.setNsPath(existingRfc.getNsPath());
        attachment.setRfcId(existingRfc.getRfcId());
        attachment.setCiId(existingRfc.getCiId());
        attachment.setReleaseNsPath(releaseNsPath);
        return cmRfcMrgProcessor.upsertCiRfc(attachment, userId);
    }

    private void createRfcAndRelationFromExportCi(CmsRfcCI componentRfc, ExportCi exportCi, String relationName, String releaseNsPath, String userId) {
        CmsRfcCI rfc = newFromExportCi(exportCi);
        rfc.setNsPath(componentRfc.getNsPath());
        rfc.setReleaseNsPath(releaseNsPath);
        CmsRfcCI newRfc = cmRfcMrgProcessor.upsertCiRfc(rfc, userId);
        CmsRfcRelation relationRfc = trUtil.bootstrapRelationRfc(componentRfc.getCiId(), newRfc.getCiId(), relationName, componentRfc.getNsPath(), releaseNsPath, null);
        if (componentRfc.getRfcId() > 0) {
            relationRfc.setFromRfcId(componentRfc.getRfcId());
        }
        relationRfc.setToRfcId(newRfc.getRfcId());
        cmRfcMrgProcessor.upsertRelationRfc(relationRfc, userId);
    }

    private void importMonitor(CmsRfcCI designPlatform, CmsRfcCI componentRfc, ExportCi monitorExp,
                               String platNsPath, String releaseNsPath, String packNsPath, String userId) {
        List<CmsRfcCI> existingMonitors = cmRfcMrgProcessor.getDfDjCiNakedLower(componentRfc.getNsPath(), DESIGN_MONITOR_CLASS, monitorExp.getName(), null);
        if (!existingMonitors.isEmpty()) {
            CmsRfcCI existingRfc = existingMonitors.get(0);
            mergeRfcWithExportCi(existingRfc, monitorExp, releaseNsPath, userId);
        } else {
            boolean isCustomMonitor = false;
            if (monitorExp.getAttributes() != null) {
                if ("true".equalsIgnoreCase(monitorExp.getAttributes().get(CmsConstants.MONITOR_CUSTOM_ATTR))) {
                    isCustomMonitor = true;
                }
            }
            if (isCustomMonitor) {
                createRfcAndRelationFromExportCi(componentRfc, monitorExp, FLAVOR.getWatchedByRelation(), releaseNsPath, userId);
            } else {
                //get the template monitor and merge with monitor from export
                String tmplMonitorName = extractTmplMonitorNameFromDesignMonitor(designPlatform.getCiName(), componentRfc.getCiName(), monitorExp.getName());
                List<CmsCI> mgmtComponents = cmProcessor.getCiBy3(packNsPath, MGMT_PREFIX + monitorExp.getType(), tmplMonitorName);
                if (mgmtComponents.isEmpty()) {
                    //can not find template - abort
                    throw new DesignExportException(DesignExportException.CMS_CANT_FIGURE_OUT_TEMPLATE_FOR_MANIFEST_ERROR,
                            BAD_TEMPLATE_ERROR_MSG + packNsPath + ";" + monitorExp.getType() + ";" + tmplMonitorName);
                }

                CmsCI tmplMonitor = mgmtComponents.get(0);
                CmsRfcCI monitorRfc = designRfcProcessor.popRfcCiFromTemplate(tmplMonitor, "catalog", platNsPath, releaseNsPath);
                applyExportCiToTemplateRfc(monitorExp, monitorRfc);
                monitorRfc = cmRfcMrgProcessor.upsertCiRfc(monitorRfc, userId);

                //create watchedBy relation from mgmt relation
                createRelationFromMgmt(componentRfc, tmplMonitor, monitorRfc, FLAVOR.getWatchedByRelation(), userId);
            }
        }
    }

    private String extractTmplMonitorNameFromDesignMonitor(String platName, String componentName, String designMonitorName) {
        String prefix = platName + "-" + componentName + "-";
        if (designMonitorName.length() > prefix.length())
            return designMonitorName.substring(designMonitorName.indexOf(prefix) + prefix.length());
        else
            return designMonitorName;
    }

    private void createRelationFromMgmt(CmsRfcCI fromRfc, CmsCI template, CmsRfcCI componentRfc, String relationName, String userId) {
        List<CmsCIRelation> mgmtRels = cmProcessor.getToCIRelationsNaked(template.getCiId(), relationName, MGMT_PREFIX + fromRfc.getCiClassName());
        if (mgmtRels.isEmpty()) {
            //can not find template relation - abort
            throw new DesignExportException(DesignExportException.CMS_CANT_FIND_REQUIRES_FOR_CI_ERROR,
                    CANT_FIND_RELATION_ERROR_MSG.replace("$relationName", relationName) + template.getCiId());
        }

        CmsRfcRelation designRel = designRfcProcessor.popRfcRelFromTemplate(mgmtRels.get(0), "base", componentRfc.getNsPath(), componentRfc.getReleaseNsPath());
        upsertRelRfc(designRel, fromRfc, componentRfc, componentRfc.getReleaseId(), userId);
    }

    private void processMgmtDependsOnRels(CmsRfcCI designPlatform, CmsCI mgmtCi, CmsRfcCI componentRfc, String userId) {
        //first do from
        List<CmsCIRelation> mgmtDependsOnFromRels = cmProcessor.getFromCIRelations(mgmtCi.getCiId(), MGMT_DEPENDS_ON_RELATION, null);
        for (CmsCIRelation mgmtDependsOn : mgmtDependsOnFromRels) {
            //lets find corresponding design component
            CmsCI targetMgmtCi = mgmtDependsOn.getToCi();
            List<AttrQueryCondition> attrConditions = new ArrayList<AttrQueryCondition>();
            AttrQueryCondition condition = new AttrQueryCondition();
            condition.setAttributeName("template");
            condition.setCondition("eq");
            condition.setAvalue(targetMgmtCi.getCiName());
            attrConditions.add(condition);
            List<CmsRfcRelation> designRequiresRels = cmRfcMrgProcessor.getFromCIRelationsByAttrs(
                    designPlatform.getCiId(),
                    FLAVOR.getRequiresRelation(),
                    null,
                    "catalog." + trUtil.getLongShortClazzName(targetMgmtCi.getCiClassName()), "dj", attrConditions);
            //now we need to create all dependsOn rels for these guys, if any
            for (CmsRfcRelation requires : designRequiresRels) {
                CmsRfcRelation designDependsOnRel = designRfcProcessor.popRfcRelFromTemplate(mgmtDependsOn, "catalog", componentRfc.getNsPath(), componentRfc.getReleaseNsPath());
                upsertRelRfc(designDependsOnRel, componentRfc, requires.getToRfcCi(), componentRfc.getReleaseId(), userId);
            }
        }
        //Now "To" 
        List<CmsCIRelation> mgmtDependsOnToRels = cmProcessor.getToCIRelations(mgmtCi.getCiId(), MGMT_DEPENDS_ON_RELATION, null);
        for (CmsCIRelation mgmtDependsOn : mgmtDependsOnToRels) {
            //lets find corresponding design component
            CmsCI targetMgmtCi = mgmtDependsOn.getFromCi();
            List<AttrQueryCondition> attrConditions = new ArrayList<AttrQueryCondition>();
            AttrQueryCondition condition = new AttrQueryCondition();
            condition.setAttributeName("template");
            condition.setCondition("eq");
            condition.setAvalue(targetMgmtCi.getCiName());
            attrConditions.add(condition);
            List<CmsRfcRelation> designRequiresRels = cmRfcMrgProcessor.getFromCIRelationsByAttrs(
                    designPlatform.getCiId(),
                    FLAVOR.getRequiresRelation(),
                    null,
                    "catalog." + trUtil.getLongShortClazzName(targetMgmtCi.getCiClassName()), "dj", attrConditions);
            //now we need to create all dependsOn rels for these guys, if any
            for (CmsRfcRelation requires : designRequiresRels) {
                CmsRfcRelation designDependsOnRel = designRfcProcessor.popRfcRelFromTemplate(mgmtDependsOn, "catalog", componentRfc.getNsPath(), componentRfc.getReleaseNsPath());
                upsertRelRfc(designDependsOnRel, requires.getToRfcCi(), componentRfc, componentRfc.getReleaseId(), userId);
            }
        }


    }

    private void upsertRelRfc(CmsRfcRelation relRfc, CmsRfcCI fromRfc, CmsRfcCI toRfc, long releaseId, String userId) {
        relRfc.setToCiId(toRfc.getCiId());
        if (toRfc.getRfcId() > 0) {
            relRfc.setToRfcId(toRfc.getRfcId());
        }
        relRfc.setFromCiId(fromRfc.getCiId());
        if (fromRfc.getRfcId() > 0) {
            relRfc.setFromRfcId(fromRfc.getRfcId());
        }
        if (releaseId > 0) {
            relRfc.setReleaseId(releaseId);
        }
        relRfc.setCreatedBy(userId);
        relRfc.setUpdatedBy(userId);
        cmRfcMrgProcessor.upsertRelationRfc(relRfc, userId);

    }

    private CmsRfcCI newFromExportCi(ExportCi eCi) {
        return bootstrapNew(eCi, new CmsRfcCI());
    }

    private CmsRfcCI bootstrapNew(ExportCi eCi, CmsRfcCI rfc) {
        rfc.setCiName(eCi.getName());
        rfc.setCiClassName(eCi.getType());
        if (eCi.getAttributes() != null) {
            for (Map.Entry<String, String> attr : eCi.getAttributes().entrySet()) {
                CmsRfcAttribute rfcAttr = new CmsRfcAttribute();
                rfcAttr.setAttributeName(attr.getKey());
                rfcAttr.setNewValue(attr.getValue());
                rfcAttr.setOwner(OWNER_DESIGN);
                rfc.addAttribute(rfcAttr);
            }
        }
        return rfc;
    }

    private CmsRfcCI newFromExportCiWithMdAttrs(ExportCi eCi, String nsPath, String releaseNsPath, Set<String> attrsToBootstrap) {
        CmsRfcCI rfc = trUtil.bootstrapRfc(eCi.getName(), eCi.getType(), nsPath, releaseNsPath, attrsToBootstrap);
        return bootstrapNew(eCi, rfc);
    }

    private void applyExportCiToTemplateRfc(ExportCi eCi, CmsRfcCI rfc) {
        rfc.setCiName(eCi.getName());
        rfc.setCiClassName(eCi.getType());
        if (eCi.getAttributes() != null) {
            for (Map.Entry<String, String> attr : eCi.getAttributes().entrySet()) {
                String newValue = attr.getValue();
                if (newValue != null && newValue.startsWith(ENCRYPTED_PREFIX)) {
                    newValue = parseEncryptedImportValue(newValue);
                }
                if (rfc.getAttribute(attr.getKey()) != null) {
                    rfc.getAttribute(attr.getKey()).setNewValue(newValue);
                    rfc.getAttribute(attr.getKey()).setOwner(OWNER_DESIGN);
                } else {
                    CmsRfcAttribute rfcAttr = new CmsRfcAttribute();
                    rfcAttr.setAttributeName(attr.getKey());
                    rfcAttr.setNewValue(newValue);
                    rfcAttr.setOwner(OWNER_DESIGN);
                    rfc.addAttribute(rfcAttr);
                }
            }
        }
    }


  
    private String checkVar4Export(CmsCI var, boolean checkLock) {
        if ("true".equals(var.getAttribute(ATTR_SECURE).getDjValue())) {
            if (checkLock && !OWNER_DESIGN.equals(var.getAttribute(ATTR_ENC_VALUE).getOwner())) {
                return null;
            }
            return DUMMY_ENCRYPTED_EXP_VALUE;
        } else {
            if (checkLock && !OWNER_DESIGN.equals(var.getAttribute(ATTR_VALUE).getOwner())) {
                return null;
            }
            return var.getAttribute(ATTR_VALUE).getDjValue();
        }
    }

  

   

    private String getPackNsPath(CmsRfcCI platform) {
        return "/public/" +
                platform.getAttribute("source").getNewValue() + "/packs/" +
                platform.getAttribute("pack").getNewValue() + "/" +
                platform.getAttribute("version").getNewValue();
    }

   
}

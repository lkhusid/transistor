package com.oneops.transistor.service;

import com.oneops.cms.cm.domain.CmsCI;
import com.oneops.cms.cm.domain.CmsCIAttribute;
import com.oneops.cms.cm.domain.CmsCIRelation;
import com.oneops.cms.cm.domain.CmsCIRelationAttribute;
import com.oneops.cms.cm.service.CmsCmProcessor;
import com.oneops.cms.dj.domain.CmsRelease;
import com.oneops.cms.dj.domain.CmsRfcCI;
import com.oneops.cms.dj.domain.CmsRfcRelation;
import com.oneops.cms.dj.service.CmsCmRfcMrgProcessor;
import com.oneops.cms.dj.service.CmsRfcProcessor;
import com.oneops.cms.exceptions.DJException;
import com.oneops.cms.util.CmsConstants;
import com.oneops.transistor.exceptions.DesignExportException;
import com.oneops.transistor.export.domain.*;
import org.apache.log4j.Logger;

import java.util.*;
import java.util.stream.Collectors;

public class EnvironmentExportProcessor {
    private static final String USER_EXPORT = "export";
    private static Logger logger = Logger.getLogger(EnvironmentExportProcessor.class);
    private CmsCmProcessor cmProcessor;
    private CmsCmRfcMrgProcessor cmRfcMrgProcessor;
    private CmsRfcProcessor rfcProcessor;
    private TransUtil trUtil;
    private static final ExportFlavor FLAVOR = ExportFlavor.MANIFEST;
    private static final String BAD_ENV_ID_ERROR_MSG = "Environment does not exists with id=";
    private static final String OPEN_RELEASE_ERROR_MSG = "Design have open release. Please commit/discard before import.";
    private static final String CANT_FIND_PLATFORM_BY_NAME_ERROR_MSG = "Can not find platform with name: $toPlaform, used in links of platform $fromPlatform";
    private static final String CANT_FIND_COMPONENT_BY_NAME_ERROR_MSG = "Can not find component with name: $toComponent, used in depends of component $fromComponent";
    private static final String IMPORT_ERROR_PLAT_COMP = "Platform/Component - ";
    private static final String IMPORT_ERROR_PLAT_COMP_ATTACH = "Platform/Component/Attachment/Monitor - ";

    private static final String ACCOUNT_CLOUD_CLASS = "account.Cloud";
    private static final String MONITOR_CLASS = "manifest.Monitor";

    private static final String OWNER_MANIFEST = "manifest";
    private static final String ATTR_SECURE = "secure";
    private static final String ATTR_VALUE = "value";
    private static final String ATTR_ENC_VALUE = "encrypted_value";


    public void setRfcProcessor(CmsRfcProcessor rfcProcessor) {
        this.rfcProcessor = rfcProcessor;
    }

    public void setTrUtil(TransUtil trUtil) {
        this.trUtil = trUtil;
    }


    public void setCmRfcMrgProcessor(CmsCmRfcMrgProcessor cmRfcMrgProcessor) {
        this.cmRfcMrgProcessor = cmRfcMrgProcessor;
    }


    public void setCmProcessor(CmsCmProcessor cmProcessor) {
        this.cmProcessor = cmProcessor;
    }


    private DesignExportSimple exportDesign(long ciId, Long[] platformIds) {
        CmsCI ci = cmProcessor.getCiById(ciId);
        if (ci == null) {
            throw new DesignExportException(DesignExportException.CMS_NO_CI_WITH_GIVEN_ID_ERROR, BAD_ENV_ID_ERROR_MSG + ciId);
        }
        DesignExportSimple des = new DesignExportSimple();

        List<CmsCIRelation> composedOfs;
        if (platformIds == null || platformIds.length == 0) {
            List<CmsCIRelation> globalVarRels = cmProcessor.getToCIRelations(ciId, FLAVOR.getGlobalVarRelation(), FLAVOR.getGlobalVarClass());     //get the global vars
            for (CmsCIRelation gvRel : globalVarRels) {
                des.addVariable(gvRel.getFromCi().getCiName(), checkVar4Export(gvRel.getFromCi(), false));
            }
            composedOfs = cmProcessor.getFromCIRelations(ciId, FLAVOR.getComposedOfRelation(), FLAVOR.getPlatformClass());
        } else {
            composedOfs = cmProcessor.getFromCIRelationsByToCiIds(ciId, FLAVOR.getComposedOfRelation(), null, Arrays.asList(platformIds));
        }

        for (CmsCIRelation composedOf : composedOfs) {
            CmsCI platform = composedOf.getToCi();
            PlatformExport pe = stripAndSimplify(PlatformExport.class, platform, FLAVOR.getOwner()); 
            des.addPlatformExport(pe); //export platform ci
            
            //local vars
            List<CmsCIRelation> localVarRels = cmProcessor.getToCIRelations(platform.getCiId(), FLAVOR.getLocalVarRelation(), FLAVOR.getLocalVarClass());
            for (CmsCIRelation lvRel : localVarRels) {
                pe.addVariable(lvRel.getFromCi().getCiName(), checkVar4Export(lvRel.getFromCi(), true));
            }
            addComponents(platform, pe);
            addConsumesRelations(platform, pe);
        }
        return des;
    }


    private static String checkVar4Export(CmsCI var, boolean checkLock) {
        if ("true".equals(var.getAttribute(ATTR_SECURE).getDjValue())) {
            if (checkLock && !OWNER_MANIFEST.equals(var.getAttribute(ATTR_ENC_VALUE).getOwner())) {
                return null;
            }
            return RfcUtil.DUMMY_ENCRYPTED_EXP_VALUE;
        } else {
            if (checkLock && !OWNER_MANIFEST.equals(var.getAttribute(ATTR_VALUE).getOwner())) {
                return null;
            }
            return var.getAttribute(ATTR_VALUE).getDjValue();
        }
    }

    private void addConsumesRelations(CmsCI platform, PlatformExport pe) {
        List<CmsCIRelation> requiresRels = cmProcessor.getFromCIRelations(platform.getCiId(), FLAVOR.getConsumesRelation(), null);
        for (CmsCIRelation requires : requiresRels) {
            pe.addConsume(stripAndSimplify(ExportRelation.class, requires));
        }
    }


    private void addComponents(CmsCI platform, PlatformExport pe) {
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
                if (isOptional || !isEmpty(eCi.getAttachments()) || !isEmpty(eCi.getMonitors()) || !isEmpty(eCi.getScaling()) || (eCi.getAttributes()!=null && !eCi.getAttributes().isEmpty())) {
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
                String attrValue = RfcUtil.checkEncrypted(relation.getAttribute(attrName).getDjValue());
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
                    String attrValue = RfcUtil.checkEncrypted(ci.getAttribute(attrName).getDjValue());
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


    EnvironmentExportSimple exportEnvironment(long envId, Long[] platformIds, String scope) {
        CmsCI env = cmProcessor.getCiById(envId);
        if (env == null) {
            throw new DesignExportException(DesignExportException.CMS_NO_CI_WITH_GIVEN_ID_ERROR, BAD_ENV_ID_ERROR_MSG + envId);
        }
        trUtil.verifyScope(env, scope);
        
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


        DesignExportSimple design = exportDesign(envId, platformIds);
        exportSimple.setManifest(design);
        return exportSimple;
    }


    /**
     * IMPORT
     *
     * @param environmentId
     * @param userId
     * @param ees
     * @return
     */
    long importEnvironment(long environmentId, String userId, String scope, EnvironmentExportSimple ees) {
        CmsCI environment = cmProcessor.getCiById(environmentId);
        if (environment == null) {
            throw new DesignExportException(DesignExportException.CMS_NO_CI_WITH_GIVEN_ID_ERROR, BAD_ENV_ID_ERROR_MSG + environmentId);
        }
        trUtil.verifyScope(environment, scope);

        String nsPath = environment.getNsPath() + "/" + environment.getCiName();

        List<CmsRelease> openReleases = rfcProcessor.getLatestRelease(nsPath + "/manifest", "open");
        if (openReleases.size() > 0) {
            throw new DesignExportException(DesignExportException.DJ_OPEN_RELEASE_FOR_NAMESPACE_ERROR, OPEN_RELEASE_ERROR_MSG);
        }
        updateCi(environment, ees.getEnvironment());  // update environment attributes
        
        
        // consumes (Clouds)
        Map<String, CmsCIRelation> map = cmProcessor.getFromCIRelations(environmentId, FLAVOR.getConsumesRelation(), ACCOUNT_CLOUD_CLASS).stream().collect(Collectors.toMap(x -> x.getToCi().getCiName(), x -> x));
        for (ExportRelation cloudRel : ees.getConsumes()) {  // consumes
            CmsCIRelation rel = map.remove(cloudRel.getName());
            if (rel != null) {
                updateRelation(rel, cloudRel.getAttributes(), userId);
            } else {
                logger.warn("There is no cloud:" + cloudRel.getName()); // this is error cloud doesn't exist 
            }
        }
        if (map.size()>0) {
            logger.warn("Environment has "+map.size()+" extra clouds that aren't a part of export");
        }


        // delivers (Relays)
        Map<String, CmsCIRelation> map1 = cmProcessor.getFromCIRelations(environmentId, "manifest.Delivers", "manifest.relay.email.Relay").stream().collect(Collectors.toMap(x -> x.getToCi().getCiName(), x -> x));
        for (ExportCi deliver : ees.getRelays()) {
            CmsCIRelation del = map1.remove(deliver.getName());
            if (del == null) {
                addCi(nsPath, deliver);
            } else {
                updateCi(del.getToCi(), deliver);
            }
            for (CmsCIRelation existingRel : map1.values()) {
                cmProcessor.deleteRelation(existingRel.getCiRelationId());
                cmProcessor.deleteCI(existingRel.getToCiId(), USER_EXPORT);
            }
        }
        return importDesignWithFlavor(userId, ees.getManifest(), nsPath + "/manifest");
    }


    private void updateRelation(CmsCIRelation relation, Map<String, String> snapshotAttributes, String userId) {
        CmsRfcRelation rel = new CmsRfcRelation();
        rel.setNsPath(relation.getNsPath());
        rel.setToCiId(relation.getToCiId());
        rel.setFromCiId(relation.getFromCiId());
        rel.setRelationName(relation.getRelationName());

        Map<String, CmsCIRelationAttribute> existingAttributes = relation.getAttributes();
        relation.setRelationId(relation.getRelationId());
        for (String key : snapshotAttributes.keySet()) {
            CmsCIRelationAttribute ciAttribute = existingAttributes.remove(key);
            String value = snapshotAttributes.get(key);
            if (ciAttribute == null || (ciAttribute.getDfValue() == null && value != null) || (ciAttribute.getDfValue() != null && !ciAttribute.getDfValue().equals(value))) {
                rel.addAttribute(RfcUtil.getAttributeRfc(key, value, OWNER_MANIFEST));
            }
        }
        if (!rel.getAttributes().isEmpty()) {
            logger.info("Updating relation:" + relation.getRelationName() + "@" + relation.getNsPath());
            cmRfcMrgProcessor.upsertRelationRfc(rel, userId);
        } else {
            logger.info("Nothing to update in relation:" + relation.getRelationName() + "@" + relation.getNsPath());
        }
    }


    private CmsCI addCi(String ns, ExportCi eci) {
        CmsCI ci = new CmsCI();
        ci.setCiName(eci.getName());
        ci.setCiClassName(eci.getType());
        ci.setNsPath(ns);
        if (eci.getAttributes() != null) {
            for (Map.Entry<String, String> attr : eci.getAttributes().entrySet()) {
                CmsCIAttribute rfcAttr = new CmsCIAttribute();
                if (attr.getValue() != null) {
                    rfcAttr.setAttributeName(attr.getKey());
                    rfcAttr.setDfValue(attr.getValue());
                    rfcAttr.setDjValue(attr.getValue());
                    ci.addAttribute(rfcAttr);
                }
            }
        }
        logger.info("adding ci:" + ci.getCiName() + "@" + ci.getNsPath());
        return cmProcessor.createCI(ci);
    }


    private void updateCi(CmsCI ci, ExportCi eci) {
        Map<String, CmsCIAttribute> existingAttributes = ci.getAttributes();
        Map<String, String> snapshotAttributes = eci.getAttributes();
        boolean needToUpdate = false;
        for (String key : snapshotAttributes.keySet()) {
            CmsCIAttribute ciAttribute = existingAttributes.get(key);
            String value = snapshotAttributes.get(key);
            if (ciAttribute != null && ((ciAttribute.getDfValue() == null && value != null) || (ciAttribute.getDfValue() != null && !ciAttribute.getDfValue().equals(value)))) {
                ciAttribute.setDfValue(value);
                ciAttribute.setDjValue(value);
                needToUpdate = true;
            }
        }
        if (needToUpdate) {
            logger.info("Updating:" + ci.getCiName() + "@" + ci.getNsPath());
            cmProcessor.updateCI(ci);
        } else {
            logger.info("no need to update:" + ci.getCiName() + "@" + ci.getNsPath());
        }
    }


    private long importDesignWithFlavor(String userId, DesignExportSimple des, String designNsPath) {
        if (des.getVariables() != null && !des.getVariables().isEmpty()) {
            importGlobalVars(designNsPath, des.getVariables(), userId);
        }
        for (PlatformExport platformExp : des.getPlatforms()) {

            List<CmsRfcCI> existingPlatRfcs = cmRfcMrgProcessor.getDfDjCiNsLike(designNsPath, platformExp.getType(), platformExp.getName(), null);
            if (existingPlatRfcs.size() > 0) {
                CmsRfcCI existingPlat = existingPlatRfcs.get(0);
                String platNsPath = existingPlat.getNsPath();
                if (platformExp.getVariables() != null) {//local vars
                    importLocalVars(existingPlat.getCiId(), platNsPath, designNsPath, platformExp.getVariables(), userId);
                }
                if (platformExp.getConsumes() != null) {// consumes
                    importConsumes(existingPlat.getCiId(), platformExp.getConsumes(), userId);
                }
                if (platformExp.getComponents() != null) { // components
                    importComponents(existingPlat, platformExp.getComponents(), platNsPath, designNsPath, userId);
                    importDepends(platformExp.getComponents(), platNsPath, designNsPath, userId);
                }
            } else {
                logger.warn("platform " + platformExp.getName() + "@" + designNsPath + "doesn't exist. ");
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

    private void importConsumes(long ciId, List<ExportRelation> consumes, String userId) {
        Map<String, List<ExportRelation>> relationMap = consumes.stream().collect(Collectors.groupingBy(ExportRelation::getName));
        List<CmsCIRelation> clouds = cmProcessor.getFromCIRelations(ciId, FLAVOR.getConsumesRelation(), ACCOUNT_CLOUD_CLASS);
        for (CmsCIRelation consume : clouds) {
            List<ExportRelation> list = relationMap.get(consume.getToCi().getCiName());
            if (list != null && list.size() > 0) {
                ExportRelation match = list.get(0);
                Map<String, String> attributes = match.getAttributes();
                updateRelation(consume, attributes, userId);
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
                    RfcUtil.bootstrapRelationRfc(LinksTo, fromPlatform, toPlatform, (long) 0, userId);
                    cmRfcMrgProcessor.upsertRelationRfc(LinksTo, userId);
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
                        RfcUtil.bootstrapRelationRfc(dependsOn, fromComponent, toComponent, (long) 0, userId);
                        cmRfcMrgProcessor.upsertRelationRfc(dependsOn, userId);
                    }
                }
            }
        }
    }


    private void importGlobalVars(String designNsPath, Map<String, String> globalVars, String userId) {

        for (Map.Entry<String, String> var : globalVars.entrySet()) {
            List<CmsRfcCI> existingVars = cmRfcMrgProcessor.getDfDjCiNakedLower(designNsPath, FLAVOR.getGlobalVarClass(), var.getKey(), null);
            Set<String> attrsToBootstrap = new HashSet<>();
            CmsRfcCI varBaseRfc;
            if (RfcUtil.isEncrypted(var.getValue())) {
                attrsToBootstrap.add(ATTR_SECURE);
                attrsToBootstrap.add(ATTR_ENC_VALUE);
                varBaseRfc = trUtil.bootstrapRfc(var.getKey(), FLAVOR.getGlobalVarClass(), designNsPath, designNsPath, attrsToBootstrap);
                varBaseRfc.getAttribute(ATTR_SECURE).setNewValue("true");
                varBaseRfc.getAttribute(ATTR_ENC_VALUE).setNewValue(RfcUtil.parseEncryptedImportValue(var.getValue()));
            } else {
                attrsToBootstrap.add(ATTR_VALUE);
                varBaseRfc = trUtil.bootstrapRfc(var.getKey(), FLAVOR.getGlobalVarClass(), designNsPath, designNsPath, attrsToBootstrap);
                varBaseRfc.getAttribute(ATTR_VALUE).setNewValue(var.getValue());
            }

            if (!existingVars.isEmpty()) {
                CmsRfcCI existingVar = existingVars.get(0);
                varBaseRfc.setCiId(existingVar.getCiId());
                varBaseRfc.setRfcId(existingVar.getRfcId());
                cmRfcMrgProcessor.upsertCiRfc(varBaseRfc, userId);
            }
        }
    }


    private void importLocalVars(long platformId, String platformNsPath, String releaseNsPath, Map<String, String> localVars, String userId) {

        for (Map.Entry<String, String> var : localVars.entrySet()) {
            List<CmsRfcCI> existingVars = cmRfcMrgProcessor.getDfDjCiNakedLower(platformNsPath, FLAVOR.getLocalVarClass(), var.getKey(), null);
            Set<String> attrsToBootstrap = new HashSet<>();
            CmsRfcCI varBaseRfc;
            String varValue;
            if (var.getValue() == null) {
                varValue = "";
            } else {
                varValue = var.getValue();
            }

            if (RfcUtil.isEncrypted(varValue)) {
                attrsToBootstrap.add(ATTR_SECURE);
                attrsToBootstrap.add(ATTR_ENC_VALUE);
                varBaseRfc = trUtil.bootstrapRfc(var.getKey(), FLAVOR.getLocalVarClass(), platformNsPath, releaseNsPath, attrsToBootstrap);
                varBaseRfc.getAttribute(ATTR_SECURE).setNewValue("true");
                varBaseRfc.getAttribute(ATTR_ENC_VALUE).setNewValue(RfcUtil.parseEncryptedImportValue(varValue));
                varBaseRfc.getAttribute(ATTR_ENC_VALUE).setOwner(OWNER_MANIFEST);

            } else {
                attrsToBootstrap.add(ATTR_VALUE);
                varBaseRfc = trUtil.bootstrapRfc(var.getKey(), FLAVOR.getLocalVarClass(), platformNsPath, releaseNsPath, attrsToBootstrap);
                varBaseRfc.getAttribute(ATTR_VALUE).setNewValue(varValue);
                varBaseRfc.getAttribute(ATTR_VALUE).setOwner(OWNER_MANIFEST);
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


    private void importComponents(CmsRfcCI designPlatform, List<ComponentExport> compExpList, String platNsPath, String releaseNsPath, String userId) {
        for (ComponentExport compExpCi : compExpList) {
            List<CmsRfcCI> existingComponent = cmRfcMrgProcessor.getDfDjCiNakedLower(platNsPath, compExpCi.getType(), compExpCi.getName(), null);
            CmsRfcCI componentRfc;
            try {
                if (existingComponent.size() > 0) {
                    CmsRfcCI existingRfc = existingComponent.get(0);
                    CmsRfcCI component = mergeRfcWithExportCi(existingRfc, compExpCi, releaseNsPath);
                    componentRfc = cmRfcMrgProcessor.upsertCiRfc(component, userId);
                    importAttachments(componentRfc, compExpCi, releaseNsPath, designPlatform, userId);
                    importMonitors(componentRfc, compExpCi, releaseNsPath, designPlatform, userId);
                }
            } catch (DJException dje) {
                dje.printStackTrace();
                //missing required attributes
                throw new DesignExportException(dje.getErrorCode(), IMPORT_ERROR_PLAT_COMP
                        + designPlatform.getCiName()
                        + "/" + compExpCi.getName() + ":" + dje.getMessage());
            }
        }
    }

    private String getComponentImportError(CmsRfcCI designPlatform, ComponentExport compExpCi, ExportCi ciExp, String message) {
        return IMPORT_ERROR_PLAT_COMP_ATTACH
                + designPlatform.getCiName()
                + "/" + compExpCi.getName()
                + "/" + ciExp.getName() + ":" + message;
    }

    private void importAttachments(CmsRfcCI componentRfc, ComponentExport compExpCI, String releaseNsPath, CmsRfcCI designPlatform, String userId) {
        List<ExportCi> attachments = compExpCI.getAttachments();
        if (attachments != null) {
            for (ExportCi attachmentExp : attachments) {
                try {
                    List<CmsRfcCI> existingAttachments = cmRfcMrgProcessor.getDfDjCiNakedLower(componentRfc.getNsPath(), FLAVOR.getAttachmentClass(), attachmentExp.getName(), null);
                    if (!existingAttachments.isEmpty()) {
                        CmsRfcCI rfcCI = mergeRfcWithExportCi(existingAttachments.get(0), attachmentExp, releaseNsPath);
                        cmRfcMrgProcessor.upsertCiRfc(rfcCI, userId);
                    }
                } catch (DJException dje) {
                    throw new DesignExportException(dje.getErrorCode(), getComponentImportError(designPlatform, compExpCI, attachmentExp, dje.getMessage()));
                }
            }
        }

    }

    private CmsRfcCI mergeRfcWithExportCi(CmsRfcCI existingRfc, ExportCi exportCi, String releaseNsPath) {
        CmsRfcCI rfcCI = RfcUtil.bootstrapNewRfc(exportCi.getName(), exportCi.getType(), exportCi.getAttributes(), OWNER_MANIFEST);
        rfcCI.setNsPath(existingRfc.getNsPath());
        rfcCI.setRfcId(existingRfc.getRfcId());
        rfcCI.setCiId(existingRfc.getCiId());
        rfcCI.setReleaseNsPath(releaseNsPath);
        return rfcCI;
    }


    private void importMonitors(CmsRfcCI componentRfc, ComponentExport compExpCi, String releaseNsPath, CmsRfcCI designPlatform, String userId) {
        List<ExportCi> monitors = compExpCi.getMonitors();
        if (monitors != null) {
            for (ExportCi monitorExp : compExpCi.getMonitors()) {
                try {
                    List<CmsRfcCI> existingMonitors = cmRfcMrgProcessor.getDfDjCiNakedLower(componentRfc.getNsPath(), MONITOR_CLASS, monitorExp.getName(), null);
                    if (!existingMonitors.isEmpty()) {
                        CmsRfcCI rfcCI = mergeRfcWithExportCi(existingMonitors.get(0), monitorExp, releaseNsPath);
                        cmRfcMrgProcessor.upsertCiRfc(rfcCI, userId);
                    }
                } catch (DJException dje) {
                    throw new DesignExportException(dje.getErrorCode(), getComponentImportError(designPlatform, compExpCi, monitorExp, dje.getMessage()));
                }
            }
        }
    }


}

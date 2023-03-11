package com.dataiku.dip.plugins.elasticsearch_utils;

import org.springframework.beans.factory.annotation.Autowired;

import com.dataiku.dip.cuspol.CustomPolicyHooks;
import com.dataiku.dip.security.AuthCtx;
import com.dataiku.dip.connections.DSSConnection;
import com.dataiku.dip.coremodel.SerializedDataset;
import com.dataiku.dip.coremodel.InfoMessage.FixabilityCategory;
import com.dataiku.dip.coremodel.InfoMessage.MessageCode;
import com.dataiku.dip.datasets.elasticsearch.ElasticSearchDatasetHandler;
import com.dataiku.dip.exceptions.CodedException;
import com.dataiku.dip.plugins.presets.PluginPreset;
import com.dataiku.dip.plugins.RegularPluginsRegistryService;
import com.dataiku.dip.server.services.TaggableObjectsService.TaggableObject;
import com.dataiku.dip.server.datasets.DatasetSaveService.DatasetCreationContext;
import com.dataiku.dip.utils.DKULogger;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.util.List;


public class elasticsearch_hide_fields_hooks extends CustomPolicyHooks {
    
    private static DKULogger logger = DKULogger.getLogger("dku.plugins.elasticsearch-hide-fields.hooks");

    private static MessageCode mc = new MessageCode() {
        @Override
        public String getCode() {
            return "ERR_ES_DATASET_CREATION";
        }

        @Override
        public String getCodeTitle() {
            return "Cannot create dataset";
        }

        @Override
        public FixabilityCategory getFixability() {
            return FixabilityCategory.ADMIN_SETTINGS_PLUGINS;
        }
    };
    
    @Autowired private RegularPluginsRegistryService regularPluginsRegistryService;

    @Override
    public void onPreObjectSave(AuthCtx user, TaggableObject before, TaggableObject after) throws Exception {
        if(after.getClass() == SerializedDataset.class 
                && this.isElasticSearchDataset((SerializedDataset)after)
                && !((SerializedDataset)after).managed) {
            SerializedDataset ds = (SerializedDataset)after;

            String[] columnsList = getColumnsListToHide(ds);
            
            // If the list contains at least one column name check if dataset schema contains columns mapped in the ES index template
            if (columnsList.length > 0) {
                // Get Elasticsearch dataset cutsom query DSL
                String dsQueryDsl = ((ElasticSearchDatasetHandler.Config)ds.getParams()).customQueryDsl;

                // Query DSL has to be a non-empty string
                if(dsQueryDsl == null || dsQueryDsl.trim().isEmpty()) {
                    throw new CodedException(mc, "Elasticsearch dataset " + ds.name + " cannot be created with an empty query DSL.");
                }

                // Query DSL has to be a valid Elasticsearch query DSL
                if(!isValidElasticsearchQueryDsl(dsQueryDsl)) {
                    throw new CodedException(mc, "Query DSL of dataset " + ds.name + " is not a valid JSON.");
                }

                JsonArray excludedColumnsArray = new JsonArray();

                // For each column to exclude, addd it to the source exclude array
                for (String col : columnsList) {
                    excludedColumnsArray.add(col);   
                }
                
                JsonObject dsQueryDslObj = JsonParser.parseString(dsQueryDsl).getAsJsonObject();
                JsonObject _sourceObj;

                // If the _source key already exists, append columns which have to be hidden to the exclude JsonArray if it exists.
                if(dsQueryDslObj.has("_source") && dsQueryDslObj.get("_source").isJsonObject()) {
                    _sourceObj = dsQueryDslObj.getAsJsonObject("_source");
                    if (_sourceObj.has("exclude") && _sourceObj.get("exclude").isJsonArray()) {
                        for (String col : columnsList) {
                            if (!jsonArrayHasValue(_sourceObj.getAsJsonArray("exclude"), col)) {
                                _sourceObj.getAsJsonArray("exclude").add(col);
                            }
                        }
                    }
                    // Else add the exclude JsonArray in the already existing _source JsonObject
                    else {
                        _sourceObj.add("exclude", excludedColumnsArray);
                    }
                }
                // If the _source key doesn't exists in query DSL, create it with the exclude JsonArray containg columns which have to be hidden
                else {
                     _sourceObj = new JsonObject();
                    _sourceObj.add("exclude", excludedColumnsArray);
                   
                }
                dsQueryDslObj.add("_source", _sourceObj);
               

                ((ElasticSearchDatasetHandler.Config)ds.getParams()).customQueryDsl = dsQueryDslObj.toString();
                logger.info((Object)("Configuring custom query DSL " + dsQueryDslObj.toString() + " for dataset " + ds.name));
            }
        }
    }
    
    private boolean isElasticSearchDataset(SerializedDataset ds) {
        return ds.type.equals("ElasticSearch");
    }

    private boolean isValidElasticsearchQueryDsl(String json) {
        try {
            JsonObject queryDslObj = JsonParser.parseString(json).getAsJsonObject();
            return queryDslObj.has("query");
        } catch (JsonSyntaxException e) {
            return false;
        }
    }

    private boolean jsonArrayHasValue(JsonArray json, String value) {
        for(int i = 0; i < json.size(); i++) {  // iterate through the JsonArray
            // first I get the 'i' JsonElement as a JsonObject, then I get the key as a string and I compare it with the value
            if (json.get(i).getAsString().equals(value)) return true;
        }
        return false;
    }

    private String[] getColumnsListToHide(SerializedDataset ds) throws CodedException,IOException {
        JsonElement dsConnectionElt = JsonParser.parseString(ds.getParams().getConnection());
        Boolean connectionFound = false;
        String[] columnsList = new String[0];
        List<PluginPreset> presets = regularPluginsRegistryService.getSettings("elasticsearch-hide-fields").presets;

        // Find if dataset connection has been defined in a plugin preset
        // If yes, extract the name of columns which have to be hidden
        for (PluginPreset p : presets) {
            if (p.pluginConfig.has("es-connections") && p.pluginConfig.has("columns")) {
                JsonArray connections = p.pluginConfig.get("es-connections").getAsJsonArray(); 
                logger.info((Object)("DEBUGCODE " + p.pluginConfig.toString()));

                // Throw an error if ES connection if found in more than one preset
                if(connectionFound && connections.contains(dsConnectionElt.getAsJsonPrimitive())) {
                    throw new CodedException(mc, "Connection " + dsConnectionElt.getAsJsonPrimitive().getAsString() + " is defined in multiple elasticsearch-hide-fields plugin presets. Unable to define which preset has to be used. Please contact a DSS administrator.");
                }

                // Extract columns to hide for the first connection in presets matching ES connection name
                if(!connectionFound && connections.contains(dsConnectionElt.getAsJsonPrimitive())) {
                    connectionFound = true;
                    String columns = p.pluginConfig.get("columns").getAsString();
                    columnsList = columns.length() > 0 ? columns.split(",") : columnsList;
                }
            }
        }
        return columnsList;
    }

    @Override
    public void onPreDatasetCreation(AuthCtx user, SerializedDataset serializedDataset, DatasetCreationContext context) throws Exception {
     // To be implemented

    }

    @Override
    public void onPreConnectionSave(AuthCtx user, DSSConnection before, DSSConnection after) throws Exception {
     // To be implemented
    }
}

# DSS plugin - Hide Elasticsearch fields

The purpose of this plugin is to allow DSS admins to hide fields containing sensitive data in Elasticsearch external datasets (unmanaged) to comply with data security policies or data regulations, such as GDPR, around data privacy and protection.

The plugins relies on Elasticsearch **source filtering** feature (https://www.elastic.co/guide/en/elasticsearch/reference/7.17/search-fields.html#source-filtering) to filter out sensitive data from Elasticsearch documents.

## Compatibility

DSS 11.0 or higher and Elasticsearch 7.x or higher are required.

## Usage
### Plugin configuration
Multiple **presets** can be configured in plugin settings to configure multiple hide fields scenarios. The following settings have to be configured in each configuration **preset** :

* **Elasticsearch connections** : The name of the Elasticsearch connection(s) to which filtering has to be applied. Multiple connections can be configured in a single preset.
* **Columns to hide** : A comma separated list of Elasticsearch fields to hide. 

An Elasticsearch connection can only be defined in a single preset. An exception is triggered when a new dataset is created if the dataset connection is defined in more than one configuration **preset**

### Custom query DSL
All the DSS datasets created using an Elasticsearch connection protected by this plugin must be configured with a **valid custom query DSL**. 

The plugin updates the custom query DSL with source filtering configuration when the dataset is saved.
                


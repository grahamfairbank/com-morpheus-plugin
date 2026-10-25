package io.github.grahamfairbank.commorpheus

import com.morpheusdata.core.Plugin
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.Permission
import com.morpheusdata.model.User
import com.morpheusdata.views.HandlebarsRenderer
import groovy.util.logging.Slf4j

/**
 * com-morpheus: HPE Compute Ops Management (COM) data on physical HPE host pages in HPE VM Essentials 9.1+.
 *
 * Phase 1 (read-only): an "HPE COM" tab with the COM summary, health, firmware, support and alerts,
 * matched to the COM server by NIC MAC addresses, with links to the server's COM page and its iLO.
 * Settings are in Administration > Integrations > Plugins.
 *
 * Community project: not affiliated with, endorsed or supported by HPE.
 */
@Slf4j
class ComMorpheusPlugin extends Plugin {

	static final String CODE = 'com-morpheus-plugin'
	static final String PERMISSION_CODE = 'com-morpheus-view'
	static final String ACTIONS_PERMISSION = 'com-morpheus-actions'

	/** Not final so tests can give it a fake COM. */
	ComService service = new ComService()

	@Override
	String getCode() { CODE }

	@Override
	void initialize() {
		this.setName('com-morpheus')
		this.setDescription('Unofficial community plugin (not an HPE product): shows HPE Compute Ops Management data on physical HPE host pages')
		this.setAuthor('com-morpheus community project')
		this.setSourceCodeLocationUrl('https://github.com/grahamfairbank/com-morpheus-plugin')
		this.setPermissions([
			new Permission('com-morpheus: View HPE COM tab', PERMISSION_CODE, [Permission.AccessType.none, Permission.AccessType.read]),
			new Permission('com-morpheus: Run HPE COM actions', ACTIONS_PERMISSION, [Permission.AccessType.none, Permission.AccessType.full])
		])
		this.registerProvider(new ComServerTabProvider(this, morpheus))
		this.registerProvider(new ComUiLoaderProvider(this, morpheus))
		// The plugin brings its own renderer, as other community plugins with web routes do, so Morpheus 9.0 loads
		// it with its controller. The tab and loader providers build their own renderers either way.
		this.setRenderer(new HandlebarsRenderer('renderer', this.getClassLoader() ?: this.class.classLoader))
		// Routes are dispatched from getControllers() (Plugin.controllers), as in the plugin-core HTTP routing guide.
		this.setControllers([new ComController(this, morpheus)])
	}

	@Override
	void onDestroy() {
		service.shutdown()
	}

	/** Current settings, read on every use so changes apply without reloading the plugin. */
	ComSettings loadSettings() {
		try {
			return ComSettings.fromJson(morpheus.getSettings(this).blockingGet())
		} catch (Throwable e) {
			log.warn("com-morpheus: could not read plugin settings: ${e}")
			return ComSettings.fromJson(null)
		}
	}

	static boolean canView(ComSettings settings, User user) {
		if (!settings.requirePermission) return true
		String level = user?.permissions?.get(PERMISSION_CODE)
		return level in ['read', 'full']
	}

	@Override
	List<OptionType> getSettings() {
		int order = 0
		return [
			new OptionType(name: 'COM API URL', code: 'com-morpheus-api-url', fieldName: ComSettings.API_URL,
				fieldLabel: 'COM API region URL', inputType: OptionType.InputType.TEXT, required: false, displayOrder: order++,
				defaultValue: ComSettings.DEFAULT_API_URL, placeHolderText: ComSettings.DEFAULT_API_URL,
				helpText: 'The GreenLake API endpoint for your COM region: https://us-west.api.greenlake.hpe.com, https://eu-central.api.greenlake.hpe.com or https://ap-northeast.api.greenlake.hpe.com.'),
			new OptionType(name: 'Client ID', code: 'com-morpheus-client-id', fieldName: ComSettings.CLIENT_ID,
				fieldLabel: 'API client ID', inputType: OptionType.InputType.TEXT, required: false, displayOrder: order++,
				helpText: 'From a GreenLake personal API client for Compute Ops Management. It inherits the roles of the GreenLake user who created it, and the plugin acts as that user for everyone on this appliance, so use a dedicated account with the smallest COM role that works. Leave blank to hide the tab.'),
			new OptionType(name: 'Client secret', code: 'com-morpheus-client-secret', fieldName: ComSettings.CLIENT_SECRET,
				fieldLabel: 'API client secret', inputType: OptionType.InputType.PASSWORD, required: false, displayOrder: order++,
				helpText: 'From the same GreenLake Personal API client as the client ID. GreenLake shows it only once, when the client is created.'),
			new OptionType(name: 'Workspace ID', code: 'com-morpheus-workspace-id', fieldName: ComSettings.WORKSPACE_ID,
				fieldLabel: 'GreenLake workspace ID', inputType: OptionType.InputType.TEXT, required: false, displayOrder: order++,
				helpText: 'Optional. The 32-character ID from Manage Workspace. Needed only if sign-in through legacy SSO fails (new-style workspaces).'),
			new OptionType(name: 'Token URL', code: 'com-morpheus-token-url', fieldName: ComSettings.TOKEN_URL,
				fieldLabel: 'Token URL override', inputType: OptionType.InputType.TEXT, required: false, displayOrder: order++,
				helpText: 'Optional. Leave blank to detect it automatically.'),
			new OptionType(name: 'Tenant ACID', code: 'com-morpheus-tenant-acid', fieldName: ComSettings.TENANT_ACID,
				fieldLabel: 'MSP tenant ID (Tenant-Acid)', inputType: OptionType.InputType.TEXT, required: false, displayOrder: order++,
				helpText: 'Optional. Only for MSP workspaces acting on behalf of a tenant.'),
			new OptionType(name: 'COM UI URL', code: 'com-morpheus-ui-url', fieldName: ComSettings.UI_URL,
				fieldLabel: 'COM web UI URL', inputType: OptionType.InputType.TEXT, required: false, displayOrder: order++,
				placeHolderText: 'https://us-west2.compute.cloud.hpe.com',
				helpText: 'Optional. Base of the "Open in COM" links. Blank picks it from the API region.'),
			new OptionType(name: 'Proxy URL', code: 'com-morpheus-proxy-url', fieldName: ComSettings.PROXY_URL,
				fieldLabel: 'HTTP proxy for GreenLake', inputType: OptionType.InputType.TEXT, required: false, displayOrder: order++,
				placeHolderText: 'http://proxy.example.com:3128',
				helpText: 'Optional. Proxy the VME manager uses to reach *.greenlake.hpe.com and sso.common.cloud.hpe.com (no authentication).'),
			new OptionType(name: 'Require permission', code: 'com-morpheus-require-permission', fieldName: ComSettings.REQUIRE_PERMISSION,
				fieldLabel: 'Only show to roles with the com-morpheus permission', inputType: OptionType.InputType.CHECKBOX,
				required: false, displayOrder: order++, defaultValue: 'off',
				helpText: 'When on, only roles granted "com-morpheus: View HPE COM tab" see the tab. When off, everyone who can see the host can.'),
			new OptionType(name: 'Show on any host', code: 'com-morpheus-any-host', fieldName: ComSettings.ANY_HOST,
				fieldLabel: 'Show the tab on non-HPE hosts (diagnostics)', inputType: OptionType.InputType.CHECKBOX,
				required: false, displayOrder: order++, defaultValue: 'off',
				helpText: 'For troubleshooting: shows the tab on every hypervisor host, with what the plugin sees (vendor, MAC addresses).'),
			new OptionType(name: 'Enable actions', code: 'com-morpheus-enable-actions', fieldName: ComSettings.ENABLE_ACTIONS,
				fieldLabel: 'Enable actions (UID light, collect inventory, iLO reset)', inputType: OptionType.InputType.CHECKBOX,
				required: false, displayOrder: order++, defaultValue: 'off',
				helpText: 'Shows action buttons on the HPE COM tab to roles with "com-morpheus: Run HPE COM actions" at full access. Every action runs in COM as the API client\'s GreenLake user (one identity for the whole appliance), so its account needs a COM role that can run server jobs. COM logs that user; Morpheus logs who clicked. Actions only run on hosts matched by MAC or by a com-serial / com-id tag.'),
			new OptionType(name: 'Route diagnostics', code: 'com-morpheus-route-diagnostics', fieldName: ComSettings.ROUTE_DIAGNOSTICS,
				fieldLabel: 'Show plugin route diagnostics on the tab', inputType: OptionType.InputType.CHECKBOX,
				required: false, displayOrder: order++, defaultValue: 'off',
				helpText: 'For troubleshooting: adds a panel that checks whether the browser can reach this plugin\'s web routes (needed for action buttons). Reads and changes nothing.')
		]
	}
}

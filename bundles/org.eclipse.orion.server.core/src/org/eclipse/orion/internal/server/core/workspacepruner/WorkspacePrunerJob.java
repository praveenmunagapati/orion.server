/*******************************************************************************
 * Copyright (c) 2016 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.orion.internal.server.core.workspacepruner;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.ListIterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.orion.server.core.OrionConfiguration;
import org.eclipse.orion.server.core.PreferenceHelper;
import org.eclipse.orion.server.core.ServerConstants;
import org.eclipse.orion.server.core.UserEmailUtil;
import org.eclipse.orion.server.core.metastore.IMetaStore;
import org.eclipse.orion.server.core.metastore.UserInfo;
import org.eclipse.orion.server.core.users.UserConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A job used to detect and delete inactive workspaces.  If a user is inactive for a configured period of time then a notification
 * e-mail is sent to inform them of the intent to delete their workspaces if they do not log in within a configured grace period.
 * If the user remains inactive then a reminder e-mail is sent mid-way through the grace period, and then a final warning e-mail
 * two days before the planned deletion.  If the user still does not log in then their workspaces are deleted.
 *  
 * This job is run every 24 hours.
 */
public class WorkspacePrunerJob extends Job {
	private Logger logger;
	private int notificationThresholdDays;
	private int gracePeriodDays;

	private static final int MINIMUM_DELETE_THRESHOLD_DAYS = 5;
	private static final int FINAL_WARNING_THRESHOLD_DAYS = 2;
	private static final int MS_IN_DAY = 1000 * 60 * 60 * 24;
	private static final String TRUE = "true"; //$NON-NLS-1$
	private static final String UNKNOWN = "unknown"; //$NON-NLS-1$
	private static final Pattern conversionPattern = Pattern.compile("([0123456789.]+)\\s*(.)"); //$NON-NLS-1$

	public WorkspacePrunerJob() {
		super("Orion Workspace Pruner"); //$NON-NLS-1$
		
		logger = LoggerFactory.getLogger("org.eclipse.orion.server.account"); //$NON-NLS-1$

		String prefString = PreferenceHelper.getString(ServerConstants.CONFIG_WORKSPACEPRUNER_DAYCOUNT_INITIALNOTIFICATION, null);
		try {
			notificationThresholdDays = prefString == null ? 0 : Integer.valueOf(prefString).intValue();
		} catch (NumberFormatException e) {
			/* error for this is logged below */
		}
		if (notificationThresholdDays < 0) {
			logger.warn("Workspace pruner will not run because a valid value was not found for config option: " + ServerConstants.CONFIG_WORKSPACEPRUNER_DAYCOUNT_INITIALNOTIFICATION); //$NON-NLS-1$
			return;
		}

		prefString = PreferenceHelper.getString(ServerConstants.CONFIG_WORKSPACEPRUNER_DAYCOUNT_DELETIONAFTERNOTIFICATION, null);
		try {
			gracePeriodDays = prefString == null ? 0 : Integer.valueOf(prefString).intValue();
		} catch (NumberFormatException e) {
			/* error for this is logged below */
		}
		if (gracePeriodDays <= 0) {
			logger.warn("Workspace pruner will not run because a valid value was not found for config option: " + ServerConstants.CONFIG_WORKSPACEPRUNER_DAYCOUNT_DELETIONAFTERNOTIFICATION); //$NON-NLS-1$
			return;
		} else if (gracePeriodDays < MINIMUM_DELETE_THRESHOLD_DAYS) {
			gracePeriodDays = 0;
			logger.warn("Workspace pruner will not run because the minimum number of days between initial e-mail warning notification and workspace deletion is : " + MINIMUM_DELETE_THRESHOLD_DAYS); //$NON-NLS-1$
			return;
		}
	}

	@Override
	protected IStatus run(IProgressMonitor monitor) {
		if (notificationThresholdDays >= 0 && gracePeriodDays > 0) {
			if (!traverseWorkspaces()) {
				if (logger.isInfoEnabled()) {
					logger.info("Orion workspace pruner job waiting for user metadata service"); //$NON-NLS-1$
				}
				schedule(5000);
				return Status.OK_STATUS;
			}
	
			/* run the workspace pruner job again in 24 hours */
			schedule(MS_IN_DAY);
		}
		return Status.OK_STATUS;
	}

	private long convertToK(String usageString) {
		if (usageString.equals(UNKNOWN)) {
			return 0;
		}

		double quantity = 0;
		Matcher matcher = conversionPattern.matcher(usageString);
		if (matcher.find()) {
			quantity = Double.valueOf(matcher.group(1));
			String unit = matcher.group(2);
			if (unit.equalsIgnoreCase("M")) { //$NON-NLS-1$
				quantity *= 1024;
			} else if (unit.equalsIgnoreCase("G")) { //$NON-NLS-1$
				quantity *= 1024 * 1024;
			} else if (!unit.equalsIgnoreCase("K")) { //$NON-NLS-1$
				quantity = 0;
				logger.warn("Orion workspace pruner job encountered unexpected disk size unit: " + unit); //$NON-NLS-1$
			}
		} else {
			logger.warn("Orion workspace pruner job encountered unexpected disk size string: " + usageString); //$NON-NLS-1$			
		}
		return (long)quantity;
	}

	private boolean traverseWorkspaces() {
		if (logger.isInfoEnabled()) {
			logger.info("Orion workspace pruner job started"); //$NON-NLS-1$
		}

		IMetaStore metaStore = OrionConfiguration.getMetaStore();
		List<String> userids;
		try {
			userids = metaStore.readAllUsers();
		} catch (CoreException e) {
			logger.error("Orion workspace pruner could not read all users", e); //$NON-NLS-1$
			return false;
		}
		
		long totalReclaimedK = 0;
		int prunedUserCount = 0;
		long warningThresholdMS = (long)notificationThresholdDays * MS_IN_DAY;

		DateFormat dateFormatter = DateFormat.getDateInstance(DateFormat.LONG);
		UserEmailUtil emailUtil = UserEmailUtil.getUtil();

		/* the current date */
		Calendar calendar = Calendar.getInstance();
		long now = calendar.getTimeInMillis();

		/* the date that user workspaces will be deleted if identified as inactive as of today */
		calendar.add(Calendar.DAY_OF_MONTH, gracePeriodDays);
		long deletionTimestamp = calendar.getTimeInMillis();
		String deletionDateString = dateFormatter.format(calendar.getTime());

		for (String userId : userids) {
			if (userId.indexOf("gayed") == -1) {
				continue;
			}
			logger.info("proceding for user: " + userId);
			try {
				UserInfo userInfo = metaStore.readUser(userId);
				String lastLoginProperty = userInfo.getProperty(UserConstants.LAST_LOGIN_TIMESTAMP);
				/* lastLoginProperty will be null for a user that has never activated their account */
				if (lastLoginProperty != null) {
					boolean userUpdated = false;
					long lastLoginTimestamp = Long.valueOf(lastLoginProperty).longValue();
					long diff = now - lastLoginTimestamp;
					if (diff < warningThresholdMS) {
						/* there has been recent activity, so ensure that properties related to pruning are all clear */
						if (userInfo.getProperty(UserConstants.WORKSPACEPRUNER_STARTDATE) != null) {
							userInfo.setProperty(UserConstants.WORKSPACEPRUNER_STARTDATE, null);
							userInfo.setProperty(UserConstants.WORKSPACEPRUNER_REMINDERSENT, null);
							userInfo.setProperty(UserConstants.WORKSPACEPRUNER_FINALWARNINGSENT, null);
							userInfo.setProperty(UserConstants.WORKSPACEPRUNER_ENDDATE, null);
							userUpdated = true;
						}
					} else {
						String startDateProperty = userInfo.getProperty(UserConstants.WORKSPACEPRUNER_STARTDATE);
						if (startDateProperty == null) {
							/* the inactivity threshold has just been passed */
							try {
								String lastLoginDateString = dateFormatter.format(new Date(lastLoginTimestamp));
								emailUtil.sendInactiveWorkspaceNotification(userInfo, lastLoginDateString, deletionDateString, false);
								userInfo.setProperty(UserConstants.WORKSPACEPRUNER_STARTDATE, String.valueOf(now));
								userInfo.setProperty(UserConstants.WORKSPACEPRUNER_ENDDATE, String.valueOf(deletionTimestamp));
								userUpdated = true;
								logger.info("Initial inactive user notification sent to " + userInfo.getProperty(UserConstants.EMAIL) + ", last login was: " + lastLoginDateString); //$NON-NLS-1 //$NON-NLS-2
							} catch (Exception e) {
								/* since the userInfo properties were not set as a result of this exception, this will be attempted again the next time the job runs */ 
								logger.warn("Orion workspace pruner failed its attempt to send an initial notification to inactive user: " + userInfo.getProperty(UserConstants.EMAIL), e); //$NON-NLS-1
							}
						} else {
							/* the first notification e-mail has already been sent, which established the target deletion date */
							
							String endDateProperty = userInfo.getProperty(UserConstants.WORKSPACEPRUNER_ENDDATE);
							long endDate = Long.valueOf(endDateProperty).longValue();
							String finalWarningSent = userInfo.getProperty(UserConstants.WORKSPACEPRUNER_FINALWARNINGSENT);
							if (endDate < now) {
								/* the target deletion date has been passed */

								String reminderSent = userInfo.getProperty(UserConstants.WORKSPACEPRUNER_REMINDERSENT);
								if (reminderSent != null || finalWarningSent != null) {
									/* enough e-mails have been successfully sent, so delete the workspace(s) */

									File userRoot = metaStore.getUserHome(userId).toLocalFile(EFS.NONE, null);
									long initialSize = convertToK(getFolderSize(userRoot));
									List<String> workspaceIds = userInfo.getWorkspaceIds();
									ListIterator<String> iterator = workspaceIds.listIterator();
									while (iterator.hasNext()) {
										metaStore.deleteWorkspace(userId, iterator.next());
									}
									prunedUserCount++;
									long reclaimedK = initialSize - convertToK(getFolderSize(userRoot));
									totalReclaimedK += reclaimedK;
									logger.info("Deleted workspaces for user " + userInfo.getProperty(UserConstants.EMAIL) + ", space reclaimed: " + toConsumableString(reclaimedK)); //$NON-NLS-1 //$NON-NLS-2
								} else {
									/* not enough e-mails have been successfully sent, so attempt to push the deletion date out by the final warning threshold and re-send a final warning */

									calendar.setTimeInMillis(now);
									calendar.add(Calendar.DAY_OF_MONTH, FINAL_WARNING_THRESHOLD_DAYS);
									try {
										emailUtil.sendInactiveWorkspaceFinalWarning(userInfo, dateFormatter.format(new Date(calendar.getTimeInMillis())));
										userInfo.setProperty(UserConstants.WORKSPACEPRUNER_FINALWARNINGSENT, TRUE);
										userInfo.setProperty(UserConstants.WORKSPACEPRUNER_ENDDATE, String.valueOf(calendar.getTimeInMillis()));
										userUpdated = true;
										logger.info("Final inactive user warning sent to " + userInfo.getProperty(UserConstants.EMAIL) + " (bumped), last login was: " + dateFormatter.format(new Date(lastLoginTimestamp))); //$NON-NLS-1 //$NON-NLS-2
									} catch (Exception e) {
										/* since the userInfo properties were not set as a result of this exception, this will be attempted again the next time the job runs */ 
										logger.warn("Orion workspace pruner failed its attempt to send a (bumped) final warning to inactive user: " + userInfo.getProperty(UserConstants.EMAIL), e); //$NON-NLS-1
									}
								}
							} else {
								/* currently within the grace period, determine whether a reminder or final warning e-mail is due to be sent */
								if (finalWarningSent == null) {
									long nowPlusThreshold = now + MS_IN_DAY * FINAL_WARNING_THRESHOLD_DAYS;
									if (endDate < nowPlusThreshold) {
										/* due to send the final warning */
										try {
											emailUtil.sendInactiveWorkspaceFinalWarning(userInfo, dateFormatter.format(new Date(endDate)));
											userInfo.setProperty(UserConstants.WORKSPACEPRUNER_FINALWARNINGSENT, TRUE);
											userUpdated = true;
											logger.info("Final inactive user warning sent to " + userInfo.getProperty(UserConstants.EMAIL) + ", last login was: " + dateFormatter.format(new Date(lastLoginTimestamp))); //$NON-NLS-1 //$NON-NLS-2
										} catch (Exception e) {
											/* since the userInfo properties were not set as a result of this exception, this will be attempted again the next time the job runs */ 
											logger.warn("Orion workspace pruner failed its attempt to send a final warning to inactive user: " + userInfo.getProperty(UserConstants.EMAIL), e); //$NON-NLS-1
										}
									} else {
										String reminderSent = userInfo.getProperty(UserConstants.WORKSPACEPRUNER_REMINDERSENT);
										if (reminderSent == null) {
											long startDate = Long.valueOf(startDateProperty).longValue();
											long middle = startDate + (endDate - startDate) / 2;
											if (middle < now) {
												/* due to send the reminder */
												try {
													String lastLoginDateString = dateFormatter.format(new Date(lastLoginTimestamp));
													emailUtil.sendInactiveWorkspaceNotification(userInfo, lastLoginDateString, dateFormatter.format(new Date(endDate)), true);
													userInfo.setProperty(UserConstants.WORKSPACEPRUNER_REMINDERSENT, TRUE);
													userUpdated = true;
													logger.info("Reminder inactive user e-mail sent to " + userInfo.getProperty(UserConstants.EMAIL) + ", last login was: " + dateFormatter.format(new Date(lastLoginTimestamp))); //$NON-NLS-1 //$NON-NLS-2
												} catch (Exception e) {
													/* since the userInfo properties were not set as a result of this exception, this will be attempted again the next time the job runs */ 
													logger.warn("Orion workspace pruner failed its attempt to send a reminder e-mail to inactive user: " + userInfo.getProperty(UserConstants.EMAIL), e); //$NON-NLS-1
												}
											}
										}
									}
								}
							}
						}
					}
					if (userUpdated) {
						metaStore.updateUser(userInfo);
					}
				}
			} catch (CoreException e) {
				logger.error("Orion workspace pruner error while processing user: " + userId, e); //$NON-NLS-1$
			}
		}

		if (prunedUserCount > 0) {
			logger.info("Summary: Orion workspace pruner deleted workspaces for " + prunedUserCount + " users, total space reclaimed: " + toConsumableString(totalReclaimedK)); //$NON-NLS-1$ //$NON-NLS-2$
		}

		if (logger.isInfoEnabled()) {
			logger.info("Orion workspace pruner job completed"); //$NON-NLS-1$
		}

		return true;
	}

	private String toConsumableString(long quantity) {
		/* incoming quantity is assumed to be in K */
		String unit = "KB"; //$NON-NLS-1$
		if (quantity > 1024) {
			quantity /= 1024;
			unit = "MB"; //$NON-NLS-1$
		}
		if (quantity > 1024) {
			quantity /= 1024;
			unit = "GB"; //$NON-NLS-1$
		}
		return quantity + unit;
	}

	private String getFolderSize(File folder) {
		StringBuffer commandOutput = new StringBuffer();
		Process process;
		try {
			// execute the "du -hs" command to get the space used by this folder
			process = Runtime.getRuntime().exec("du -hs " + folder.toString()); //$NON-NLS-1$
			process.waitFor();
			BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

			String line = "";
			while ((line = reader.readLine()) != null) {
				commandOutput.append(line + "\n"); //$NON-NLS-1$
			}
		} catch (Exception e) {
			return UNKNOWN;
		}

		String size = commandOutput.toString();
		if (size.indexOf("\t") == -1) { //$NON-NLS-1$
			return UNKNOWN;
		}
		return size.substring(0, size.indexOf("\t")); //$NON-NLS-1$
	}
}

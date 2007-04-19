/**********************************************************************************
*
* $Id$
*
***********************************************************************************
*
* Copyright (c) 2005, 2006 The Regents of the University of California, The MIT Corporation
*
* Licensed under the Educational Community License, Version 1.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*      http://www.opensource.org/licenses/ecl1.php
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*
**********************************************************************************/

package org.sakaiproject.tool.gradebook.business.impl;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hibernate.Hibernate;
import org.hibernate.HibernateException;
import org.hibernate.Query;
import org.hibernate.Session;
import org.hibernate.StaleObjectStateException;
import org.hibernate.TransientObjectException;
import org.sakaiproject.component.gradebook.BaseHibernateManager;
import org.sakaiproject.service.gradebook.shared.ConflictingAssignmentNameException;
import org.sakaiproject.service.gradebook.shared.ConflictingCategoryNameException;
import org.sakaiproject.service.gradebook.shared.ConflictingSpreadsheetNameException;
import org.sakaiproject.service.gradebook.shared.GradebookService;
import org.sakaiproject.service.gradebook.shared.StaleObjectModificationException;
import org.sakaiproject.tool.gradebook.AbstractGradeRecord;
import org.sakaiproject.tool.gradebook.Assignment;
import org.sakaiproject.tool.gradebook.AssignmentGradeRecord;
import org.sakaiproject.tool.gradebook.Category;
import org.sakaiproject.tool.gradebook.Comment;
import org.sakaiproject.tool.gradebook.CourseGrade;
import org.sakaiproject.tool.gradebook.CourseGradeRecord;
import org.sakaiproject.tool.gradebook.GradableObject;
import org.sakaiproject.tool.gradebook.Gradebook;
import org.sakaiproject.tool.gradebook.GradingEvent;
import org.sakaiproject.tool.gradebook.GradingEvents;
import org.sakaiproject.tool.gradebook.Spreadsheet;
import org.sakaiproject.tool.gradebook.business.GradebookManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.hibernate3.HibernateCallback;
import org.springframework.orm.hibernate3.HibernateOptimisticLockingFailureException;

/**
 * Manages Gradebook persistence via hibernate.
 */
public class GradebookManagerHibernateImpl extends BaseHibernateManager
        implements GradebookManager {

    private static final Log log = LogFactory.getLog(GradebookManagerHibernateImpl.class);
    
    // Special logger for data contention analysis.
    private static final Log logData = LogFactory.getLog(GradebookManagerHibernateImpl.class.getName() + ".GB_DATA");

    public void removeAssignment(final Long assignmentId) throws StaleObjectModificationException {
        HibernateCallback hc = new HibernateCallback() {
            public Object doInHibernate(Session session) throws HibernateException {
                Assignment asn = (Assignment)session.load(Assignment.class, assignmentId);
                Gradebook gradebook = asn.getGradebook();
                asn.setRemoved(true);
                session.update(asn);
                if(log.isInfoEnabled()) log.info("Assignment " + asn.getName() + " has been removed from " + gradebook);
                return null;
            }
        };
        getHibernateTemplate().execute(hc);
    }

    public Gradebook getGradebook(Long id) {
        return (Gradebook)getHibernateTemplate().load(Gradebook.class, id);
    }

    public List getAssignmentGradeRecords(final Assignment assignment, final Collection studentUids) {
        HibernateCallback hc = new HibernateCallback() {
            public Object doInHibernate(Session session) throws HibernateException {
                if(studentUids == null || studentUids.size() == 0) {
                    if(log.isInfoEnabled()) log.info("Returning no grade records for an empty collection of student UIDs");
                    return new ArrayList();
                } else if (assignment.isRemoved()) {
                    return new ArrayList();                	
                }

                Query q = session.createQuery("from AssignmentGradeRecord as agr where agr.gradableObject.id=:gradableObjectId order by agr.pointsEarned");
                q.setLong("gradableObjectId", assignment.getId().longValue());
                List records = filterGradeRecordsByStudents(q.list(), studentUids);
                return records;
            }
        };
        return (List)getHibernateTemplate().execute(hc);
    }

    public List getPointsEarnedCourseGradeRecords(final CourseGrade courseGrade, final Collection studentUids) {
    	HibernateCallback hc = new HibernateCallback() {
    		public Object doInHibernate(Session session) throws HibernateException {
    			if(studentUids == null || studentUids.size() == 0) {
    				if(log.isInfoEnabled()) log.info("Returning no grade records for an empty collection of student UIDs");
    				return new ArrayList();
    			}

    			Query q = session.createQuery("from CourseGradeRecord as cgr where cgr.gradableObject.id=:gradableObjectId");
    			q.setLong("gradableObjectId", courseGrade.getId().longValue());
    			List records = filterAndPopulateCourseGradeRecordsByStudents(courseGrade, q.list(), studentUids);

    			Long gradebookId = courseGrade.getGradebook().getId();
    			Gradebook gradebook = getGradebook(gradebookId);
    			List cates = getCategories(gradebookId);
    			//double totalPointsPossible = getTotalPointsInternal(gradebookId, session);
    			//if(log.isDebugEnabled()) log.debug("Total points = " + totalPointsPossible);

    			for(Iterator iter = records.iterator(); iter.hasNext();) {
    				CourseGradeRecord cgr = (CourseGradeRecord)iter.next();
    				//double totalPointsEarned = getTotalPointsEarnedInternal(gradebookId, cgr.getStudentId(), session);
    				List totalEarned = getTotalPointsEarnedInternal(gradebookId, cgr.getStudentId(), session, gradebook, cates);
    				double totalPointsEarned = ((Double)totalEarned.get(0)).doubleValue();
    				double literalTotalPointsEarned = ((Double)totalEarned.get(1)).doubleValue();
    				double totalPointsPossible = getTotalPointsInternal(gradebookId, session, gradebook, cates, cgr.getStudentId());
    				cgr.initNonpersistentFields(totalPointsPossible, totalPointsEarned, literalTotalPointsEarned);
    				if(log.isDebugEnabled()) log.debug("Points earned = " + cgr.getPointsEarned());
    			}

    			return records;
    		}
    	};
    	return (List)getHibernateTemplate().execute(hc);
    }

    public List getPointsEarnedCourseGradeRecordsWithStats(final CourseGrade courseGrade, final Collection studentUids) {
    	// Get good class-wide statistics by including all students, whether
    	// the caller is specifically interested in their grade records or not.
    	Long gradebookId = courseGrade.getGradebook().getId();
    	Set allStudentUids = getAllStudentUids(getGradebookUid(gradebookId));
    	List courseGradeRecords = getPointsEarnedCourseGradeRecords(courseGrade, allStudentUids);
    	courseGrade.calculateStatistics(courseGradeRecords, allStudentUids.size());

    	// Filter out the grade records which weren't specified.
    	courseGradeRecords = filterGradeRecordsByStudents(courseGradeRecords, studentUids);

    	return courseGradeRecords;
    }

    public void addToGradeRecordMap(Map gradeRecordMap, List gradeRecords) {
		for (Iterator iter = gradeRecords.iterator(); iter.hasNext(); ) {
			AbstractGradeRecord gradeRecord = (AbstractGradeRecord)iter.next();
			String studentUid = gradeRecord.getStudentId();
			Map studentMap = (Map)gradeRecordMap.get(studentUid);
			if (studentMap == null) {
				studentMap = new HashMap();
				gradeRecordMap.put(studentUid, studentMap);
			}
			studentMap.put(gradeRecord.getGradableObject().getId(), gradeRecord);
		}
    }
    
    public void addToCategoryResultMap(Map categoryResultMap, List categories, Map gradeRecordMap, Map enrollmentMap) {
    	
    	for (Iterator stuIter = enrollmentMap.keySet().iterator(); stuIter.hasNext(); ){
    		String studentUid = (String) stuIter.next();
    		Map studentMap = (Map) gradeRecordMap.get(studentUid);
    		
    		for (Iterator iter = categories.iterator(); iter.hasNext(); ){
    			Category category = (Category) iter.next();   		
    			double total = 0;
        		double studentTotal = 0;
	    		
	    		List categoryAssignments = category.getAssignmentList();
	    		for (Iterator assignmentsIter = categoryAssignments.iterator(); assignmentsIter.hasNext(); ){
	    			Assignment assignment = (Assignment) assignmentsIter.next();
	    			if (!assignment.isCounted()) {
	    				continue;
	    			}
	    			AbstractGradeRecord gradeRecord = (AbstractGradeRecord) studentMap.get(assignment.getId());
	    			total += assignment.getPointsPossible();
	    			studentTotal += (gradeRecord != null ? gradeRecord.getPointsEarned() : 0);
	    		}
	    		Map studentCategoryMap = (Map) categoryResultMap.get(studentUid);
		    	if (studentCategoryMap == null) {
		    		studentCategoryMap = new HashMap();
		    		categoryResultMap.put(studentUid, studentCategoryMap);
		    	}
		    	Map stats = new HashMap();
		    	stats.put("studentPoints", studentTotal);
		    	stats.put("categoryPoints", total);
		    	studentCategoryMap.put(category.getId(), stats);
	    	}  	
    	}
    	int var =1; var++;
    }
    
//    public List getPointsEarnedCourseGradeRecords(final CourseGrade courseGrade, final Collection studentUids, final Collection assignments, final Map gradeRecordMap) {
//    	HibernateCallback hc = new HibernateCallback() {
//    		public Object doInHibernate(Session session) throws HibernateException {
//    			if(studentUids == null || studentUids.size() == 0) {
//    				if(log.isInfoEnabled()) log.info("Returning no grade records for an empty collection of student UIDs");
//    				return new ArrayList();
//    			}
//
//    			Query q = session.createQuery("from CourseGradeRecord as cgr where cgr.gradableObject.id=:gradableObjectId");
//    			q.setLong("gradableObjectId", courseGrade.getId().longValue());
//    			List records = filterAndPopulateCourseGradeRecordsByStudents(courseGrade, q.list(), studentUids);
//
//     			Set assignmentsNotCounted = new HashSet();
//    			double totalPointsPossible = 0;
//     			for (Iterator iter = assignments.iterator(); iter.hasNext(); ) {
//     				Assignment assignment = (Assignment)iter.next();
//     				if (assignment.isCounted()) {
//     					totalPointsPossible += assignment.getPointsPossible();
//     				} else {
//     					assignmentsNotCounted.add(assignment.getId());
//     				}
//     			}
//    			if(log.isDebugEnabled()) log.debug("Total points = " + totalPointsPossible);
//
//    			for(Iterator iter = records.iterator(); iter.hasNext();) {
//    				CourseGradeRecord cgr = (CourseGradeRecord)iter.next();
//    				double totalPointsEarned = 0;
//    				Map studentMap = (Map)gradeRecordMap.get(cgr.getStudentId());
//    				if (studentMap != null) {
//        				Collection studentGradeRecords = studentMap.values();
//    					for (Iterator gradeRecordIter = studentGradeRecords.iterator(); gradeRecordIter.hasNext(); ) {
//    						AssignmentGradeRecord agr = (AssignmentGradeRecord)gradeRecordIter.next();
//    						if (!assignmentsNotCounted.contains(agr.getGradableObject().getId())) {
//    							Double pointsEarned = agr.getPointsEarned();
//    							if (pointsEarned != null) {
//    								totalPointsEarned += pointsEarned.doubleValue();
//    							}    						
//    						}
//    					}
//    				}
//   					cgr.initNonpersistentFields(totalPointsPossible, totalPointsEarned);
//    				if(log.isDebugEnabled()) log.debug("Points earned = " + cgr.getPointsEarned());
//    			}
//
//    			return records;
//    		}
//    	};
//    	return (List)getHibernateTemplate().execute(hc);
//    }
    public List getPointsEarnedCourseGradeRecords(final CourseGrade courseGrade, final Collection studentUids, final Collection assignments, final Map gradeRecordMap) {
    	HibernateCallback hc = new HibernateCallback() {
    		public Object doInHibernate(Session session) throws HibernateException {
    			if(studentUids == null || studentUids.size() == 0) {
    				if(log.isInfoEnabled()) log.info("Returning no grade records for an empty collection of student UIDs");
    				return new ArrayList();
    			}

    			Query q = session.createQuery("from CourseGradeRecord as cgr where cgr.gradableObject.id=:gradableObjectId");
    			q.setLong("gradableObjectId", courseGrade.getId().longValue());
    			List records = filterAndPopulateCourseGradeRecordsByStudents(courseGrade, q.list(), studentUids);

    			Gradebook gradebook = getGradebook(courseGrade.getGradebook().getId());
    			List categories = getCategories(courseGrade.getGradebook().getId());
    			
     			Set assignmentsNotCounted = new HashSet();
    			double totalPointsPossible = 0;
					Map cateTotalScoreMap = new HashMap();

					for (Iterator iter = assignments.iterator(); iter.hasNext(); ) {
						Assignment assignment = (Assignment)iter.next();
						if (!assignment.isCounted()) {
   						assignmentsNotCounted.add(assignment.getId());
   					}
    			}
    			if(log.isDebugEnabled()) log.debug("Total points = " + totalPointsPossible);

    			for(Iterator iter = records.iterator(); iter.hasNext();) {
    				CourseGradeRecord cgr = (CourseGradeRecord)iter.next();
    				double totalPointsEarned = 0;
    	    	double literalTotalPointsEarned = 0;
  					Map cateScoreMap = new HashMap();
    				Map studentMap = (Map)gradeRecordMap.get(cgr.getStudentId());
    				Set assignmentsTaken = new HashSet();
    				if (studentMap != null) {
    					Collection studentGradeRecords = studentMap.values();
    					for (Iterator gradeRecordIter = studentGradeRecords.iterator(); gradeRecordIter.hasNext(); ) {
    						AssignmentGradeRecord agr = (AssignmentGradeRecord)gradeRecordIter.next();
    						if (!assignmentsNotCounted.contains(agr.getGradableObject().getId())) {
    							Double pointsEarned = agr.getPointsEarned();
    							if (pointsEarned != null) {
    								if(gradebook.getCategory_type() == GradebookService.CATEGORY_TYPE_NO_CATEGORY)
    								{
    									totalPointsEarned += pointsEarned.doubleValue();
    						    	literalTotalPointsEarned += pointsEarned.doubleValue();
              				assignmentsTaken.add(agr.getAssignment().getId());
    								}
    								else if(gradebook.getCategory_type() == GradebookService.CATEGORY_TYPE_ONLY_CATEGORY && categories != null)
    	     					{
    	        				for(int i=0; i<categories.size(); i++)
    	        				{
    	        					Category cate = (Category) categories.get(i);
    	        					if(cate != null && !cate.isRemoved() && agr.getAssignment().getCategory() != null && cate.getId().equals(agr.getAssignment().getCategory().getId()))
    	        					{
    	        						totalPointsEarned += pointsEarned.doubleValue();
        						    	literalTotalPointsEarned += pointsEarned.doubleValue();
    	            				assignmentsTaken.add(agr.getAssignment().getId());
    	        						break;
    	        					}
    	        				}
    	     					}
    			    			else if(gradebook.getCategory_type() == GradebookService.CATEGORY_TYPE_WEIGHTED_CATEGORY && categories != null)
    			    			{
    			    				for(int i=0; i<categories.size(); i++)
    			    				{
    			    					Category cate = (Category) categories.get(i);
    			    					if(cate != null && !cate.isRemoved() && agr.getAssignment().getCategory() != null && cate.getId().equals(agr.getAssignment().getCategory().getId()))
    			    					{
    		          				assignmentsTaken.add(agr.getAssignment().getId());
    		          				literalTotalPointsEarned += pointsEarned.doubleValue();
    			    						if(cateScoreMap.get(cate.getId()) != null)
    			    						{
    			    							cateScoreMap.put(cate.getId(), new Double(((Double)cateScoreMap.get(cate.getId())).doubleValue() + pointsEarned.doubleValue()));
    			    						}
    			    						else
    			    						{
    			    							cateScoreMap.put(cate.getId(), new Double(pointsEarned));
    			    						}
    			    						break;
    			    					}
    			    				}
    			    			}
    							}
    						}
    					}

    					cateTotalScoreMap.clear();
    		    	if(gradebook.getCategory_type() == GradebookService.CATEGORY_TYPE_WEIGHTED_CATEGORY && categories != null)
    		    	{
    		    		Iterator assignIter = assignments.iterator();
    		    		while (assignIter.hasNext()) 
    		    		{
    		    			Assignment asgn = (Assignment)assignIter.next();
    		    			if(assignmentsTaken.contains(asgn.getId()))
    		    			{
    		    				for(int i=0; i<categories.size(); i++)
    		    				{
    		    					Category cate = (Category) categories.get(i);
    		    					if(cate != null && !cate.isRemoved() && asgn.getCategory() != null && cate.getId().equals(asgn.getCategory().getId()))
    		    					{
    		    						if(cateTotalScoreMap.get(cate.getId()) == null)
    		    						{
    		    							cateTotalScoreMap.put(cate.getId(), asgn.getPointsPossible());
    		    						}
    		    						else
    		    						{
    		    							cateTotalScoreMap.put(cate.getId(), new Double(((Double)cateTotalScoreMap.get(cate.getId())).doubleValue() + asgn.getPointsPossible().doubleValue()));
    		    						}
    		    					}
    		    				}
    		    			}
    		    		}
    		    	}
    					
    		    	if(gradebook.getCategory_type() == GradebookService.CATEGORY_TYPE_WEIGHTED_CATEGORY)
    		    	{
    		    		for(int i=0; i<categories.size(); i++)
    		    		{
    		    			Category cate = (Category) categories.get(i);
    		    			if(cate != null && !cate.isRemoved() && cateScoreMap.get(cate.getId()) != null && cateTotalScoreMap.get(cate.getId()) != null)
    		    			{
    		    				totalPointsEarned += ((Double)cateScoreMap.get(cate.getId())).doubleValue() * cate.getWeight().doubleValue() / ((Double)cateTotalScoreMap.get(cate.getId())).doubleValue();
    		    			}
    		    		}
    		    	}
    				}

    				totalPointsPossible = 0;
    				if(!assignmentsTaken.isEmpty())
    	    	{
    	    		if(gradebook.getCategory_type() == GradebookService.CATEGORY_TYPE_WEIGHTED_CATEGORY)
    	    		{
    	    			totalPointsPossible = 1.0;
    	    		}
    	    		Iterator assignIter = assignments.iterator();
  		    		while (assignIter.hasNext()) 
  		    		{
    						Assignment assignment = (Assignment)assignIter.next();
    						if(assignment != null)
    						{
    							Double pointsPossible = assignment.getPointsPossible();
    							if(gradebook.getCategory_type() == GradebookService.CATEGORY_TYPE_NO_CATEGORY && assignmentsTaken.contains(assignment.getId()))
    							{
    	    					totalPointsPossible += pointsPossible.doubleValue();
    							}
    							else if(gradebook.getCategory_type() == GradebookService.CATEGORY_TYPE_ONLY_CATEGORY && categories != null && assignmentsTaken.contains(assignment.getId()))
    							{
    								for(int i=0; i<categories.size(); i++)
    								{
    									Category cate = (Category) categories.get(i);
    									if(cate != null && !cate.isRemoved() && assignment.getCategory() != null && cate.getId().equals(assignment.getCategory().getId()))
    									{
        	    					totalPointsPossible += pointsPossible.doubleValue();
    										break;
    									}
    								}
    							}
    						}
    					}
    	    	}
    				cgr.initNonpersistentFields(totalPointsPossible, totalPointsEarned, literalTotalPointsEarned);
    				if(log.isDebugEnabled()) log.debug("Points earned = " + cgr.getPointsEarned());
    			}

    			return records;
    		}
    	};
    	return (List)getHibernateTemplate().execute(hc);
    }

    /**
     */
    public List getAllAssignmentGradeRecords(final Long gradebookId, final Collection studentUids) {
        HibernateCallback hc = new HibernateCallback() {
            public Object doInHibernate(Session session) throws HibernateException {
                if(studentUids.size() == 0) {
                    // If there are no enrollments, no need to execute the query.
                    if(log.isInfoEnabled()) log.info("No enrollments were specified.  Returning an empty List of grade records");
                    return new ArrayList();
                } else {
                    Query q = session.createQuery("from AssignmentGradeRecord as agr where agr.gradableObject.removed=false and " +
                            "agr.gradableObject.gradebook.id=:gradebookId order by agr.pointsEarned");
                    q.setLong("gradebookId", gradebookId.longValue());
                    return filterGradeRecordsByStudents(q.list(), studentUids);
                }
            }
        };
        return (List)getHibernateTemplate().execute(hc);
    }

    /**
     * @return Returns set of student UIDs who were given scores higher than the assignment's value.
     */
    public Set updateAssignmentGradeRecords(final Assignment assignment, final Collection gradeRecordsFromCall)
            throws StaleObjectModificationException {
        // If no grade records are sent, don't bother doing anything with the db
        if(gradeRecordsFromCall.size() == 0) {
            log.debug("updateAssignmentGradeRecords called for zero grade records");
            return new HashSet();
        }

        if (logData.isDebugEnabled()) logData.debug("BEGIN: Update " + gradeRecordsFromCall.size() + " scores for gradebook=" + assignment.getGradebook().getUid() + ", assignment=" + assignment.getName());

        HibernateCallback hc = new HibernateCallback() {
            public Object doInHibernate(Session session) throws HibernateException {
                Date now = new Date();
                String graderId = authn.getUserUid();

                Set studentsWithUpdatedAssignmentGradeRecords = new HashSet();
                Set studentsWithExcessiveScores = new HashSet();

                for(Iterator iter = gradeRecordsFromCall.iterator(); iter.hasNext();) {
                	AssignmentGradeRecord gradeRecordFromCall = (AssignmentGradeRecord)iter.next();
                	gradeRecordFromCall.setGraderId(graderId);
                	gradeRecordFromCall.setDateRecorded(now);
                	try {
                		session.saveOrUpdate(gradeRecordFromCall);
                	} catch (TransientObjectException e) {
                		// It's possible that a previously unscored student
                		// was scored behind the current user's back before
                		// the user saved the new score. This translates
                		// that case into an optimistic locking failure.
                		if(log.isInfoEnabled()) log.info("An optimistic locking failure occurred while attempting to add a new assignment grade record");
                		throw new StaleObjectModificationException(e);
                	}

                	// Check for excessive (AKA extra credit) scoring.
                	if (gradeRecordFromCall.getPointsEarned() != null &&
                			gradeRecordFromCall.getPointsEarned().compareTo(assignment.getPointsPossible()) > 0) {
                 		studentsWithExcessiveScores.add(gradeRecordFromCall.getStudentId());
                	}

                	// Log the grading event, and keep track of the students with saved/updated grades
                	session.save(new GradingEvent(assignment, graderId, gradeRecordFromCall.getStudentId(), gradeRecordFromCall.getPointsEarned()));
                	studentsWithUpdatedAssignmentGradeRecords.add(gradeRecordFromCall.getStudentId());
                }
				if (logData.isDebugEnabled()) logData.debug("Updated " + studentsWithUpdatedAssignmentGradeRecords.size() + " assignment score records");

                return studentsWithExcessiveScores;
            }
        };

        Set studentsWithExcessiveScores = (Set)getHibernateTemplate().execute(hc);
        if (logData.isDebugEnabled()) logData.debug("END: Update " + gradeRecordsFromCall.size() + " scores for gradebook=" + assignment.getGradebook().getUid() + ", assignment=" + assignment.getName());
        return studentsWithExcessiveScores;
    }

	public Set updateAssignmentGradesAndComments(Assignment assignment, Collection gradeRecords, Collection comments) throws StaleObjectModificationException {
		//Set studentsWithExcessiveScores = updateAssignmentGradeRecords(assignment, gradeRecords);
		Gradebook gradebook = getGradebook(assignment.getGradebook().getId());
		Set studentsWithExcessiveScores = updateAssignmentGradeRecords(assignment, gradeRecords, gradebook.getGrade_type());
		
		updateComments(comments);
		
		return studentsWithExcessiveScores;
	}
	
	public void updateComments(final Collection comments) throws StaleObjectModificationException {
        final Date now = new Date();
        final String graderId = authn.getUserUid();

        // Unlike the complex grade update logic, this method assumes that
		// the client has done the work of filtering out any unchanged records
		// and isn't interested in throwing an optimistic locking exception for untouched records
		// which were changed by other sessions.
		HibernateCallback hc = new HibernateCallback() {
			public Object doInHibernate(Session session) throws HibernateException {
				for (Iterator iter = comments.iterator(); iter.hasNext();) {
					Comment comment = (Comment)iter.next();
					comment.setGraderId(graderId);
					comment.setDateRecorded(now);
					session.saveOrUpdate(comment);
				}
				return null;
			}
		};
		try {
			getHibernateTemplate().execute(hc);
		} catch (DataIntegrityViolationException e) {
			// If a student hasn't yet received a comment for this
			// assignment, and two graders try to save a new comment record at the
			// same time, the database should report a unique constraint violation.
			// Since that's similar to the conflict between two graders who
			// are trying to update an existing comment record at the same
			// same time, this method translates the exception into an
			// optimistic locking failure.
			if(log.isInfoEnabled()) log.info("An optimistic locking failure occurred while attempting to update comments");
			throw new StaleObjectModificationException(e);
		}
	}

	/**
     */
    public void updateCourseGradeRecords(final CourseGrade courseGrade, final Collection gradeRecordsFromCall)
            throws StaleObjectModificationException {

        if(gradeRecordsFromCall.size() == 0) {
            log.debug("updateCourseGradeRecords called with zero grade records to update");
            return;
        }
        
        if (logData.isDebugEnabled()) logData.debug("BEGIN: Update " + gradeRecordsFromCall.size() + " course grades for gradebook=" + courseGrade.getGradebook().getUid());

        HibernateCallback hc = new HibernateCallback() {
            public Object doInHibernate(Session session) throws HibernateException {
                for(Iterator iter = gradeRecordsFromCall.iterator(); iter.hasNext();) {
                    session.evict(iter.next());
                }

                Date now = new Date();
                String graderId = authn.getUserUid();
                int numberOfUpdatedGrades = 0;

                for(Iterator iter = gradeRecordsFromCall.iterator(); iter.hasNext();) {
                    // The modified course grade record
                    CourseGradeRecord gradeRecordFromCall = (CourseGradeRecord)iter.next();
                    gradeRecordFromCall.setGraderId(graderId);
                    gradeRecordFromCall.setDateRecorded(now);
                    try {
                        session.saveOrUpdate(gradeRecordFromCall);
                        session.flush();
                    } catch (StaleObjectStateException sose) {
                        if(log.isInfoEnabled()) log.info("An optimistic locking failure occurred while attempting to update course grade records");
                        throw new StaleObjectModificationException(sose);
                    }

                    // Log the grading event
                    session.save(new GradingEvent(courseGrade, graderId, gradeRecordFromCall.getStudentId(), gradeRecordFromCall.getEnteredGrade()));
                    
                    numberOfUpdatedGrades++;
                }
                if (logData.isDebugEnabled()) logData.debug("Changed " + numberOfUpdatedGrades + " course grades for gradebook=" + courseGrade.getGradebook().getUid());
                return null;
            }
        };
        try {
	        getHibernateTemplate().execute(hc);
	        if (logData.isDebugEnabled()) logData.debug("END: Update " + gradeRecordsFromCall.size() + " course grades for gradebook=" + courseGrade.getGradebook().getUid());
		} catch (DataIntegrityViolationException e) {
			// It's possible that a previously ungraded student
			// was graded behind the current user's back before
			// the user saved the new grade. This translates
			// that case into an optimistic locking failure.
			if(log.isInfoEnabled()) log.info("An optimistic locking failure occurred while attempting to update course grade records");
			throw new StaleObjectModificationException(e);
		}
    }

    public boolean isEnteredAssignmentScores(final Long assignmentId) {
        HibernateCallback hc = new HibernateCallback() {
            public Object doInHibernate(Session session) throws HibernateException {
                Integer total = (Integer)session.createQuery(
                        "select count(agr) from AssignmentGradeRecord as agr where agr.gradableObject.id=? and agr.pointsEarned is not null").
                        setLong(0, assignmentId.longValue()).
                        uniqueResult();
                if (log.isInfoEnabled()) log.info("assignment " + assignmentId + " has " + total + " entered scores");
                return total;
            }
        };
        return ((Integer)getHibernateTemplate().execute(hc)).intValue() > 0;
    }

    /**
     */
    public List getStudentGradeRecords(final Long gradebookId, final String studentId) {
        HibernateCallback hc = new HibernateCallback() {
            public Object doInHibernate(Session session) throws HibernateException {
                return session.createQuery(
                        "from AssignmentGradeRecord as agr where agr.studentId=? and agr.gradableObject.removed=false and agr.gradableObject.gradebook.id=?").
                        setString(0, studentId).
                        setLong(1, gradebookId.longValue()).
                        list();
            }
        };
        return (List)getHibernateTemplate().execute(hc);
    }
    
    private double getTotalPointsEarnedInternal(final Long gradebookId, final String studentId, final Session session) {
        double totalPointsEarned = 0;
        Iterator scoresIter = session.createQuery(
        		"select agr.pointsEarned from AssignmentGradeRecord agr, Assignment asn where agr.gradableObject=asn and agr.studentId=:student and asn.gradebook.id=:gbid and asn.removed=false and asn.notCounted=false").
        		setParameter("student", studentId).
        		setParameter("gbid", gradebookId).
        		list().iterator();
       	while (scoresIter.hasNext()) {
       		Double pointsEarned = (Double)scoresIter.next();
       		if (pointsEarned != null) {
       			totalPointsEarned += pointsEarned.doubleValue();
       		}
       	}
       	if (log.isDebugEnabled()) log.debug("getTotalPointsEarnedInternal for studentId=" + studentId + " returning " + totalPointsEarned);
       	return totalPointsEarned;
    }

    private List getTotalPointsEarnedInternal(final Long gradebookId, final String studentId, final Session session, final Gradebook gradebook, final List categories) 
    {
    	double totalPointsEarned = 0;
    	double literalTotalPointsEarned = 0;
    	Iterator scoresIter = session.createQuery(
    			"select agr.pointsEarned, asn from AssignmentGradeRecord agr, Assignment asn where agr.gradableObject=asn and agr.studentId=:student and asn.gradebook.id=:gbid and asn.removed=false and asn.notCounted=false").
    			setParameter("student", studentId).
    			setParameter("gbid", gradebookId).
    			list().iterator();

    	List assgnsList = session.createQuery(
    	"from Assignment as asn where asn.gradebook.id=:gbid and asn.removed=false and asn.notCounted=false").
    	setParameter("gbid", gradebookId).
    	list();

    	Map cateScoreMap = new HashMap();
    	Map cateTotalScoreMap = new HashMap();

    	Set assignmentsTaken = new HashSet();
    	while (scoresIter.hasNext()) {
    		Object[] returned = (Object[])scoresIter.next();
    		Double pointsEarned = (Double)returned[0];
    		Assignment go = (Assignment) returned[1];
    		if (pointsEarned != null) {
    			if(gradebook.getCategory_type() == GradebookService.CATEGORY_TYPE_NO_CATEGORY)
    			{
    				totalPointsEarned += pointsEarned.doubleValue();
    				literalTotalPointsEarned += pointsEarned.doubleValue();
    				assignmentsTaken.add(go.getId());
    			}
    			else if(gradebook.getCategory_type() == GradebookService.CATEGORY_TYPE_ONLY_CATEGORY && go != null && categories != null)
    			{
    				for(int i=0; i<categories.size(); i++)
    				{
    					Category cate = (Category) categories.get(i);
    					if(cate != null && !cate.isRemoved() && go.getCategory() != null && cate.getId().equals(go.getCategory().getId()))
    					{
    						totalPointsEarned += pointsEarned.doubleValue();
    						literalTotalPointsEarned += pointsEarned.doubleValue();
    						assignmentsTaken.add(go.getId());
    						break;
    					}
    				}
    			}
    			else if(gradebook.getCategory_type() == GradebookService.CATEGORY_TYPE_WEIGHTED_CATEGORY && go != null && categories != null)
    			{
    				for(int i=0; i<categories.size(); i++)
    				{
    					Category cate = (Category) categories.get(i);
    					if(cate != null && !cate.isRemoved() && go.getCategory() != null && cate.getId().equals(go.getCategory().getId()))
    					{
    						assignmentsTaken.add(go.getId());
    						literalTotalPointsEarned += pointsEarned.doubleValue();
    						if(cateScoreMap.get(cate.getId()) != null)
    						{
    							cateScoreMap.put(cate.getId(), new Double(((Double)cateScoreMap.get(cate.getId())).doubleValue() + pointsEarned.doubleValue()));
    						}
    						else
    						{
    							cateScoreMap.put(cate.getId(), new Double(pointsEarned));
    						}
    						break;
    					}
    				}
    			}
    		}
    	}

    	if(gradebook.getCategory_type() == GradebookService.CATEGORY_TYPE_WEIGHTED_CATEGORY && categories != null)
    	{
    		Iterator assgnsIter = assgnsList.iterator();
    		while (assgnsIter.hasNext()) 
    		{
    			Assignment asgn = (Assignment)assgnsIter.next();
    			if(assignmentsTaken.contains(asgn.getId()))
    			{
    				for(int i=0; i<categories.size(); i++)
    				{
    					Category cate = (Category) categories.get(i);
    					if(cate != null && !cate.isRemoved() && asgn.getCategory() != null && cate.getId().equals(asgn.getCategory().getId()))
    					{
    						if(cateTotalScoreMap.get(cate.getId()) == null)
    						{
    							cateTotalScoreMap.put(cate.getId(), asgn.getPointsPossible());
    						}
    						else
    						{
    							cateTotalScoreMap.put(cate.getId(), new Double(((Double)cateTotalScoreMap.get(cate.getId())).doubleValue() + asgn.getPointsPossible().doubleValue()));
    						}
    					}
    				}
    			}
    		}
    	}

    	if(assignmentsTaken.isEmpty())
    		totalPointsEarned = -1;

    	if(gradebook.getCategory_type() == GradebookService.CATEGORY_TYPE_WEIGHTED_CATEGORY)
    	{
    		for(int i=0; i<categories.size(); i++)
    		{
    			Category cate = (Category) categories.get(i);
    			if(cate != null && !cate.isRemoved() && cateScoreMap.get(cate.getId()) != null && cateTotalScoreMap.get(cate.getId()) != null)
    			{
    				totalPointsEarned += ((Double)cateScoreMap.get(cate.getId())).doubleValue() * cate.getWeight().doubleValue() / ((Double)cateTotalScoreMap.get(cate.getId())).doubleValue();
    			}
    		}
    	}

    	if (log.isDebugEnabled()) log.debug("getTotalPointsEarnedInternal for studentId=" + studentId + " returning " + totalPointsEarned);
    	List returnList = new ArrayList();
    	returnList.add(new Double(totalPointsEarned));
    	returnList.add(new Double(literalTotalPointsEarned));
    	return returnList;
    }

    //for testing
    public double getTotalPointsEarnedInternal(final Long gradebookId, final String studentId, final Gradebook gradebook, final List categories) 
    {
    	HibernateCallback hc = new HibernateCallback() {
    		public Object doInHibernate(Session session) throws HibernateException {
    			double totalPointsEarned = 0;
    			Iterator scoresIter = session.createQuery(
    			"select agr.pointsEarned, asn from AssignmentGradeRecord agr, Assignment asn where agr.gradableObject=asn and agr.studentId=:student and asn.gradebook.id=:gbid and asn.removed=false and asn.notCounted=false").
    			setParameter("student", studentId).
    			setParameter("gbid", gradebookId).
    			list().iterator();

    			List assgnsList = session.createQuery(
    			"from Assignment as asn where asn.gradebook.id=:gbid and asn.removed=false and asn.notCounted=false").
    			setParameter("gbid", gradebookId).
    			list();

    			Map cateScoreMap = new HashMap();
    			Map cateTotalScoreMap = new HashMap();

    			Set assignmentsTaken = new HashSet();
    			while (scoresIter.hasNext()) {
    				Object[] returned = (Object[])scoresIter.next();
    				Double pointsEarned = (Double)returned[0];
    				Assignment go = (Assignment) returned[1];
    				if (pointsEarned != null) {
    					if(gradebook.getCategory_type() == GradebookService.CATEGORY_TYPE_NO_CATEGORY)
    					{
    						totalPointsEarned += pointsEarned.doubleValue();
        				assignmentsTaken.add(go.getId());
    					}
    					else if(gradebook.getCategory_type() == GradebookService.CATEGORY_TYPE_ONLY_CATEGORY && go != null && categories != null)
    					{
    						for(int i=0; i<categories.size(); i++)
    						{
    							Category cate = (Category) categories.get(i);
    							if(cate != null && !cate.isRemoved() && go.getCategory() != null && cate.getId().equals(go.getCategory().getId()))
    							{
    								totalPointsEarned += pointsEarned.doubleValue();
            				assignmentsTaken.add(go.getId());
    								break;
    							}
    						}
    					}
    					else if(gradebook.getCategory_type() == GradebookService.CATEGORY_TYPE_WEIGHTED_CATEGORY && go != null && categories != null)
    					{
    						for(int i=0; i<categories.size(); i++)
    						{
    							Category cate = (Category) categories.get(i);
    							if(cate != null && !cate.isRemoved() && go.getCategory() != null && cate.getId().equals(go.getCategory().getId()))
    							{
            				assignmentsTaken.add(go.getId());
    								if(cateScoreMap.get(cate.getId()) != null)
    								{
    									cateScoreMap.put(cate.getId(), new Double(((Double)cateScoreMap.get(cate.getId())).doubleValue() + pointsEarned.doubleValue()));
    								}
    								else
    								{
    									cateScoreMap.put(cate.getId(), new Double(pointsEarned));
    								}
    								break;
    							}
    						}
    					}
    				}
    			}
    			
    			if(gradebook.getCategory_type() == GradebookService.CATEGORY_TYPE_WEIGHTED_CATEGORY && categories != null)
    			{
    				Iterator assgnsIter = assgnsList.iterator();
    				while (assgnsIter.hasNext()) 
    				{
    					Assignment asgn = (Assignment)assgnsIter.next();
    					if(assignmentsTaken.contains(asgn.getId()))
    					{
    						for(int i=0; i<categories.size(); i++)
    						{
    							Category cate = (Category) categories.get(i);
    							if(cate != null && !cate.isRemoved() && asgn.getCategory() != null && cate.getId().equals(asgn.getCategory().getId()))
    							{
    								if(cateTotalScoreMap.get(cate.getId()) == null)
    								{
    									cateTotalScoreMap.put(cate.getId(), asgn.getPointsPossible());
    								}
    								else
    								{
    									cateTotalScoreMap.put(cate.getId(), new Double(((Double)cateTotalScoreMap.get(cate.getId())).doubleValue() + asgn.getPointsPossible().doubleValue()));
    								}
    							}
    						}
    					}
    				}
    			}

    			if(assignmentsTaken.isEmpty())
    				totalPointsEarned = -1;
    			
    			if(gradebook.getCategory_type() == GradebookService.CATEGORY_TYPE_WEIGHTED_CATEGORY)
    			{
    				for(int i=0; i<categories.size(); i++)
    				{
    					Category cate = (Category) categories.get(i);
    					if(cate != null && !cate.isRemoved() && cateScoreMap.get(cate.getId()) != null && cateTotalScoreMap.get(cate.getId()) != null)
    					{
    						totalPointsEarned += ((Double)cateScoreMap.get(cate.getId())).doubleValue() * cate.getWeight().doubleValue() / ((Double)cateTotalScoreMap.get(cate.getId())).doubleValue();
    					}
    				}
    			}

    			if (log.isDebugEnabled()) log.debug("getTotalPointsEarnedInternal for studentId=" + studentId + " returning " + totalPointsEarned);
    			return totalPointsEarned;
    		}
    	};
    	return (Double)getHibernateTemplate().execute(hc);
    }

    public CourseGradeRecord getStudentCourseGradeRecord(final Gradebook gradebook, final String studentId) {
    	if (logData.isDebugEnabled()) logData.debug("About to read student course grade for gradebook=" + gradebook.getUid());
    	return (CourseGradeRecord)getHibernateTemplate().execute(new HibernateCallback() {
            public Object doInHibernate(Session session) throws HibernateException {
                CourseGradeRecord courseGradeRecord = getCourseGradeRecord(gradebook, studentId, session);
                if (courseGradeRecord == null) {
                	courseGradeRecord = new CourseGradeRecord(getCourseGrade(gradebook.getId()), studentId);
                }
                
                // Only take the hit of autocalculating the course grade if no explicit
                // grade has been entered.
                if (courseGradeRecord.getEnteredGrade() == null) {
                    // TODO We could easily get everything we need in a single query by using an outer join if we
                    // weren't mapping the different classes together into single sparsely populated
                    // tables. When we finally break up the current mungings of Assignment with CourseGrade
                    // and AssignmentGradeRecord with CourseGradeRecord, redo this section.
                	List cates = getCategories(gradebook.getId());
                	//double totalPointsPossible = getTotalPointsInternal(gradebook.getId(), session);
                	//double totalPointsEarned = getTotalPointsEarnedInternal(gradebook.getId(), studentId, session);
                	double totalPointsPossible = getTotalPointsInternal(gradebook.getId(), session, gradebook, cates, studentId);
                	List totalEarned = getTotalPointsEarnedInternal(gradebook.getId(), studentId, session, gradebook, cates);
                	double totalPointsEarned = ((Double)totalEarned.get(0)).doubleValue();
                	double literalTotalPointsEarned = ((Double)totalEarned.get(1)).doubleValue();
                	courseGradeRecord.initNonpersistentFields(totalPointsPossible, totalPointsEarned, literalTotalPointsEarned);
                }             
                return courseGradeRecord;
            }
        });
    }

    public GradingEvents getGradingEvents(final GradableObject gradableObject, final Collection studentIds) {

        // Don't attempt to run the query if there are no enrollments
        if(studentIds == null || studentIds.size() == 0) {
            log.debug("No enrollments were specified.  Returning an empty GradingEvents object");
            return new GradingEvents();
        }

        HibernateCallback hc = new HibernateCallback() {
            public Object doInHibernate(Session session) throws HibernateException, SQLException {
                List eventsList;
                if (studentIds.size() <= MAX_NUMBER_OF_SQL_PARAMETERS_IN_LIST) {
                    Query q = session.createQuery("from GradingEvent as ge where ge.gradableObject=:go and ge.studentId in (:students)");
                    q.setParameter("go", gradableObject, Hibernate.entity(GradableObject.class));
                    q.setParameterList("students", studentIds);
                    eventsList = q.list();
                } else {
                    Query q = session.createQuery("from GradingEvent as ge where ge.gradableObject=:go");
                    q.setParameter("go", gradableObject, Hibernate.entity(GradableObject.class));
                    eventsList = new ArrayList();
                    for (Iterator iter = q.list().iterator(); iter.hasNext(); ) {
                        GradingEvent event = (GradingEvent)iter.next();
                        if (studentIds.contains(event.getStudentId())) {
                            eventsList.add(event);
                        }
                    }
                }
                return eventsList;
            }
        };

        List list = (List)getHibernateTemplate().execute(hc);

        GradingEvents events = new GradingEvents();

        for(Iterator iter = list.iterator(); iter.hasNext();) {
            GradingEvent event = (GradingEvent)iter.next();
            events.addEvent(event);
        }
        return events;
    }


    /**
     */
    public List getAssignments(final Long gradebookId, final String sortBy, final boolean ascending) {
        return (List)getHibernateTemplate().execute(new HibernateCallback() {
            public Object doInHibernate(Session session) throws HibernateException {
                List assignments = getAssignments(gradebookId, session);
                sortAssignments(assignments, sortBy, ascending);
                return assignments;
            }
        });
    }

    /**
     */
    public List getAssignmentsWithStats(final Long gradebookId, final String sortBy, final boolean ascending) {
        Set studentUids = getAllStudentUids(getGradebookUid(gradebookId));
        List assignments = getAssignments(gradebookId);
        List<AssignmentGradeRecord> gradeRecords = getAllAssignmentGradeRecords(gradebookId, studentUids);
        for (Iterator iter = assignments.iterator(); iter.hasNext(); ) {
        	Assignment assignment = (Assignment)iter.next();
        	assignment.calculateStatistics(gradeRecords);
        }
        sortAssignments(assignments, sortBy, ascending);
        return assignments;
    }

    public List getAssignmentsAndCourseGradeWithStats(final Long gradebookId, final String sortBy, final boolean ascending) {
        Set studentUids = getAllStudentUids(getGradebookUid(gradebookId));
        List assignments = getAssignments(gradebookId);
        CourseGrade courseGrade = getCourseGrade(gradebookId);
        Map gradeRecordMap = new HashMap();
        List<AssignmentGradeRecord> gradeRecords = getAllAssignmentGradeRecords(gradebookId, studentUids);
        addToGradeRecordMap(gradeRecordMap, gradeRecords);
        
        for (Iterator iter = assignments.iterator(); iter.hasNext(); ) {
        	Assignment assignment = (Assignment)iter.next();
        	assignment.calculateStatistics(gradeRecords);
        }
        
        List<CourseGradeRecord> courseGradeRecords = getPointsEarnedCourseGradeRecords(courseGrade, studentUids, assignments, gradeRecordMap);
        courseGrade.calculateStatistics(courseGradeRecords, studentUids.size());
        
        sortAssignments(assignments, sortBy, ascending);
        
        // Always put the Course Grade at the end.
        assignments.add(courseGrade);

        return assignments;
    }

    private List filterAndPopulateCourseGradeRecordsByStudents(CourseGrade courseGrade, Collection gradeRecords, Collection studentUids) {
		List filteredRecords = new ArrayList();
		Set missingStudents = new HashSet(studentUids);
		for (Iterator iter = gradeRecords.iterator(); iter.hasNext(); ) {
			CourseGradeRecord cgr = (CourseGradeRecord)iter.next();
			if (studentUids.contains(cgr.getStudentId())) {
				filteredRecords.add(cgr);
				missingStudents.remove(cgr.getStudentId());
			}
		}
		for (Iterator iter = missingStudents.iterator(); iter.hasNext(); ) {
			String studentUid = (String)iter.next();
			CourseGradeRecord cgr = new CourseGradeRecord(courseGrade, studentUid);
			filteredRecords.add(cgr);
		}
		return filteredRecords;
	}

    /**
     * TODO Remove this method in favor of doing database sorting.
     *
     * @param assignments
     * @param sortBy
     * @param ascending
     */
    private void sortAssignments(List assignments, String sortBy, boolean ascending) {
        Comparator comp;
        if(Assignment.SORT_BY_NAME.equals(sortBy)) {
            comp = Assignment.nameComparator;
        } else if(Assignment.SORT_BY_MEAN.equals(sortBy)) {
            comp = Assignment.meanComparator;
        } else if(Assignment.SORT_BY_POINTS.equals(sortBy)) {
            comp = Assignment.pointsComparator;
        }else if(Assignment.releasedComparator.equals(sortBy)){
            comp = Assignment.releasedComparator;
        } else {
            comp = Assignment.dateComparator;
        }
        Collections.sort(assignments, comp);
        if(!ascending) {
            Collections.reverse(assignments);
        }
    }

    /**
     */
    public List getAssignments(Long gradebookId) {
        return getAssignments(gradebookId, Assignment.DEFAULT_SORT, true);
    }

    /**
     */
    public Assignment getAssignment(Long assignmentId) {
        return (Assignment)getHibernateTemplate().load(Assignment.class, assignmentId);
    }

    /**
     */
    public Assignment getAssignmentWithStats(Long assignmentId) {
    	Assignment assignment = getAssignment(assignmentId);
    	Long gradebookId = assignment.getGradebook().getId();
        Set studentUids = getAllStudentUids(getGradebookUid(gradebookId));
        List<AssignmentGradeRecord> gradeRecords = getAssignmentGradeRecords(assignment, studentUids);
        assignment.calculateStatistics(gradeRecords);
        return assignment;
    }

    /**
     */
    public void updateAssignment(final Assignment assignment)
        throws ConflictingAssignmentNameException, StaleObjectModificationException {
        HibernateCallback hc = new HibernateCallback() {
            public Object doInHibernate(Session session) throws HibernateException {
            	updateAssignment(assignment, session);
                return null;
            }
        };
        try {
            getHibernateTemplate().execute(hc);
        } catch (HibernateOptimisticLockingFailureException holfe) {
            if(log.isInfoEnabled()) log.info("An optimistic locking failure occurred while attempting to update an assignment");
            throw new StaleObjectModificationException(holfe);
        }
    }

    /**
     * Gets the total number of points possible in a gradebook.
     */
    public double getTotalPoints(final Long gradebookId) {
    	Double totalPoints = (Double)getHibernateTemplate().execute(new HibernateCallback() {
    		public Object doInHibernate(Session session) throws HibernateException {
    			Gradebook gradebook = getGradebook(gradebookId);
    			List cates = getCategories(gradebookId);
    			return new Double(getLiteralTotalPointsInternal(gradebookId, session, gradebook, cates));
    			//return new Double(getTotalPointsInternal(gradebookId, session));
    		}
    	});
    	return totalPoints.doubleValue();
    }
 
    private double getTotalPointsInternal(Long gradebookId, Session session) {
        double totalPointsPossible = 0;
    	Iterator assignmentPointsIter = session.createQuery(
        		"select asn.pointsPossible from Assignment asn where asn.gradebook.id=:gbid and asn.removed=false and asn.notCounted=false").
        		setParameter("gbid", gradebookId).
        		list().iterator();
        while (assignmentPointsIter.hasNext()) {
        	Double pointsPossible = (Double)assignmentPointsIter.next();
        	totalPointsPossible += pointsPossible.doubleValue();
        }
        return totalPointsPossible;
    }

    //for testing
    public double getTotalPointsInternal(final Long gradebookId, final Gradebook gradebook, final List categories, final String studentId) 
    {
    	HibernateCallback hc = new HibernateCallback() {
    		public Object doInHibernate(Session session) throws HibernateException {
    			double totalPointsPossible = 0;
    			List assgnsList = session.createQuery(
    			"select asn from Assignment asn where asn.gradebook.id=:gbid and asn.removed=false and asn.notCounted=false").
    			setParameter("gbid", gradebookId).
    			list();
    			
    			Iterator scoresIter = session.createQuery(
    			"select agr.pointsEarned, asn from AssignmentGradeRecord agr, Assignment asn where agr.gradableObject=asn and agr.studentId=:student and asn.gradebook.id=:gbid and asn.removed=false and asn.notCounted=false").
    			setParameter("student", studentId).
    			setParameter("gbid", gradebookId).
    			list().iterator();

    			Set assignmentsTaken = new HashSet();
    			while (scoresIter.hasNext()) {
    				Object[] returned = (Object[])scoresIter.next();
    				Double pointsEarned = (Double)returned[0];
    				Assignment go = (Assignment) returned[1];
    				if (pointsEarned != null) {
    					if(gradebook.getCategory_type() == GradebookService.CATEGORY_TYPE_NO_CATEGORY)
    					{
        				assignmentsTaken.add(go.getId());
    					}
    					else if(gradebook.getCategory_type() == GradebookService.CATEGORY_TYPE_ONLY_CATEGORY && go != null && categories != null)
    					{
    						for(int i=0; i<categories.size(); i++)
    						{
    							Category cate = (Category) categories.get(i);
    							if(cate != null && !cate.isRemoved() && go.getCategory() != null && cate.getId().equals(go.getCategory().getId()))
    							{
            				assignmentsTaken.add(go.getId());
    								break;
    							}
    						}
    					}
    					else if(gradebook.getCategory_type() == GradebookService.CATEGORY_TYPE_WEIGHTED_CATEGORY && go != null && categories != null)
    					{
    						for(int i=0; i<categories.size(); i++)
    						{
    							Category cate = (Category) categories.get(i);
    							if(cate != null && !cate.isRemoved() && go.getCategory() != null && cate.getId().equals(go.getCategory().getId()))
    							{
            				assignmentsTaken.add(go.getId());
    								break;
    							}
    						}
    					}
    				}
    			}

    			if(!assignmentsTaken.isEmpty())
    			{
    				if(gradebook.getCategory_type() == GradebookService.CATEGORY_TYPE_WEIGHTED_CATEGORY)
    				{
    					return 1.0;
    				}
    				Iterator assignmentIter = assgnsList.iterator();
    				while (assignmentIter.hasNext()) {
    					Assignment asn = (Assignment) assignmentIter.next();
    					if(asn != null)
    					{
    						Double pointsPossible = asn.getPointsPossible();

    						if(gradebook.getCategory_type() == GradebookService.CATEGORY_TYPE_NO_CATEGORY && assignmentsTaken.contains(asn.getId()))
    						{
    							totalPointsPossible += pointsPossible.doubleValue();
    						}
    						else if(gradebook.getCategory_type() == GradebookService.CATEGORY_TYPE_ONLY_CATEGORY && categories != null && assignmentsTaken.contains(asn.getId()))
    						{
    							for(int i=0; i<categories.size(); i++)
    							{
    								Category cate = (Category) categories.get(i);
    								if(cate != null && !cate.isRemoved() && asn.getCategory() != null && cate.getId().equals(asn.getCategory().getId()))
    								{
    									totalPointsPossible += pointsPossible.doubleValue();
    									break;
    								}
    							}
    						}
    					}
    				}
    			}
    			else
    				totalPointsPossible = -1;
    			
    			return totalPointsPossible;
    		}
    	};
    	return (Double)getHibernateTemplate().execute(hc);    	
    }
    
    private double getTotalPointsInternal(final Long gradebookId, Session session, final Gradebook gradebook, final List categories, final String studentId)
    {
    	double totalPointsPossible = 0;
    	List assgnsList = session.createQuery(
    			"select asn from Assignment asn where asn.gradebook.id=:gbid and asn.removed=false and asn.notCounted=false").
    			setParameter("gbid", gradebookId).
    			list();

    	Iterator scoresIter = session.createQuery(
    	"select agr.pointsEarned, asn from AssignmentGradeRecord agr, Assignment asn where agr.gradableObject=asn and agr.studentId=:student and asn.gradebook.id=:gbid and asn.removed=false and asn.notCounted=false").
    	setParameter("student", studentId).
    	setParameter("gbid", gradebookId).
    	list().iterator();

    	Set assignmentsTaken = new HashSet();
    	while (scoresIter.hasNext()) {
    		Object[] returned = (Object[])scoresIter.next();
    		Double pointsEarned = (Double)returned[0];
    		Assignment go = (Assignment) returned[1];
    		if (pointsEarned != null) {
    			if(gradebook.getCategory_type() == GradebookService.CATEGORY_TYPE_NO_CATEGORY)
    			{
    				assignmentsTaken.add(go.getId());
    			}
    			else if(gradebook.getCategory_type() == GradebookService.CATEGORY_TYPE_ONLY_CATEGORY && go != null && categories != null)
    			{
    				for(int i=0; i<categories.size(); i++)
    				{
    					Category cate = (Category) categories.get(i);
    					if(cate != null && !cate.isRemoved() && go.getCategory() != null && cate.getId().equals(go.getCategory().getId()))
    					{
    						assignmentsTaken.add(go.getId());
    						break;
    					}
    				}
    			}
    			else if(gradebook.getCategory_type() == GradebookService.CATEGORY_TYPE_WEIGHTED_CATEGORY && go != null && categories != null)
    			{
    				for(int i=0; i<categories.size(); i++)
    				{
    					Category cate = (Category) categories.get(i);
    					if(cate != null && !cate.isRemoved() && go.getCategory() != null && cate.getId().equals(go.getCategory().getId()))
    					{
    						assignmentsTaken.add(go.getId());
    						break;
    					}
    				}
    			}
    		}
    	}

    	if(!assignmentsTaken.isEmpty())
    	{
    		if(gradebook.getCategory_type() == GradebookService.CATEGORY_TYPE_WEIGHTED_CATEGORY)
    		{
    			return 1.0;
    		}
    		Iterator assignmentIter = assgnsList.iterator();
    		while (assignmentIter.hasNext()) {
    			Assignment asn = (Assignment) assignmentIter.next();
    			if(asn != null)
    			{
    				Double pointsPossible = asn.getPointsPossible();

    				if(gradebook.getCategory_type() == GradebookService.CATEGORY_TYPE_NO_CATEGORY && assignmentsTaken.contains(asn.getId()))
    				{
    					totalPointsPossible += pointsPossible.doubleValue();
    				}
    				else if(gradebook.getCategory_type() == GradebookService.CATEGORY_TYPE_ONLY_CATEGORY && categories != null && assignmentsTaken.contains(asn.getId()))
    				{
    					for(int i=0; i<categories.size(); i++)
    					{
    						Category cate = (Category) categories.get(i);
    						if(cate != null && !cate.isRemoved() && asn.getCategory() != null && cate.getId().equals(asn.getCategory().getId()))
    						{
    							totalPointsPossible += pointsPossible.doubleValue();
    							break;
    						}
    					}
    				}
    			}
    		}
    	}
    	else
    		totalPointsPossible = -1;

    	return totalPointsPossible;
    }

    //for test
    public double getLiteralTotalPointsInternal(final Long gradebookId, final Gradebook gradebook, final List categories)
    {
    	HibernateCallback hc = new HibernateCallback() {
    		public Object doInHibernate(Session session) throws HibernateException {
    			double totalPointsPossible = 0;
    			Iterator assignmentIter = session.createQuery(
    			"select asn from Assignment asn where asn.gradebook.id=:gbid and asn.removed=false and asn.notCounted=false").
    			setParameter("gbid", gradebookId).
    			list().iterator();
    			while (assignmentIter.hasNext()) {
    				Assignment asn = (Assignment) assignmentIter.next();
    				if(asn != null)
    				{
    					Double pointsPossible = asn.getPointsPossible();

    					if(gradebook.getCategory_type() == GradebookService.CATEGORY_TYPE_NO_CATEGORY)
    					{
    						totalPointsPossible += pointsPossible.doubleValue();
    					}
    					else if((gradebook.getCategory_type() == GradebookService.CATEGORY_TYPE_ONLY_CATEGORY || gradebook.getCategory_type() == GradebookService.CATEGORY_TYPE_WEIGHTED_CATEGORY) && categories != null)
    					{
    						for(int i=0; i<categories.size(); i++)
    						{
    							Category cate = (Category) categories.get(i);
    							if(cate != null && !cate.isRemoved() && asn.getCategory() != null && cate.getId().equals(asn.getCategory().getId()))
    							{
    								totalPointsPossible += pointsPossible.doubleValue();
    								break;
    							}
    						}
    					}
    				}
    			}
    			return totalPointsPossible;
    		}
    	};
    	return (Double)getHibernateTemplate().execute(hc);    	
    }

    private double getLiteralTotalPointsInternal(final Long gradebookId, Session session, final Gradebook gradebook, final List categories)
    {
    	double totalPointsPossible = 0;
    	Iterator assignmentIter = session.createQuery(
    			"select asn from Assignment asn where asn.gradebook.id=:gbid and asn.removed=false and asn.notCounted=false").
    			setParameter("gbid", gradebookId).
    			list().iterator();
    	while (assignmentIter.hasNext()) {
    		Assignment asn = (Assignment) assignmentIter.next();
    		if(asn != null)
    		{
    			Double pointsPossible = asn.getPointsPossible();

    			if(gradebook.getCategory_type() == GradebookService.CATEGORY_TYPE_NO_CATEGORY)
    			{
    				totalPointsPossible += pointsPossible.doubleValue();
    			}
    			else if((gradebook.getCategory_type() == GradebookService.CATEGORY_TYPE_ONLY_CATEGORY || gradebook.getCategory_type() == GradebookService.CATEGORY_TYPE_WEIGHTED_CATEGORY) && categories != null)
    			{
    				for(int i=0; i<categories.size(); i++)
    				{
    					Category cate = (Category) categories.get(i);
    					if(cate != null && !cate.isRemoved() && asn.getCategory() != null && cate.getId().equals(asn.getCategory().getId()))
    					{
    						totalPointsPossible += pointsPossible.doubleValue();
    						break;
    					}
    				}
    			}
    		}
    	}
    	return totalPointsPossible;
    }

    public Gradebook getGradebookWithGradeMappings(final Long id) {
		return (Gradebook)getHibernateTemplate().execute(new HibernateCallback() {
			public Object doInHibernate(Session session) throws HibernateException {
				Gradebook gradebook = (Gradebook)session.load(Gradebook.class, id);
				Hibernate.initialize(gradebook.getGradeMappings());
				return gradebook;
			}
		});
	}


    /**
     *
     * @param spreadsheetId
     * @return
     */
    public Spreadsheet getSpreadsheet(final Long spreadsheetId) {
        return (Spreadsheet)getHibernateTemplate().load(Spreadsheet.class, spreadsheetId);
    }

    /**
     *
     * @param gradebookId
     * @return
     */
    public List getSpreadsheets(final Long gradebookId) {
        return (List)getHibernateTemplate().execute(new HibernateCallback() {
            public Object doInHibernate(Session session) throws HibernateException {
                List spreadsheets = getSpreadsheets(gradebookId, session);
                return spreadsheets;
            }
        });
    }

    /**
     *
     * @param spreadsheetId
     */
    public void removeSpreadsheet(final Long spreadsheetId)throws StaleObjectModificationException {

        HibernateCallback hc = new HibernateCallback() {
            public Object doInHibernate(Session session) throws HibernateException {
                Spreadsheet spt = (Spreadsheet)session.load(Spreadsheet.class, spreadsheetId);
                session.delete(spt);
                if(log.isInfoEnabled()) log.info("Spreadsheet " + spt.getName() + " has been removed from gradebook" );

                return null;
            }
        };
        getHibernateTemplate().execute(hc);

    }

    /**
     *
     * @param spreadsheet
     */
    public void updateSpreadsheet(final Spreadsheet spreadsheet)throws ConflictingAssignmentNameException, StaleObjectModificationException  {
            HibernateCallback hc = new HibernateCallback() {
                public Object doInHibernate(Session session) throws HibernateException {
                    // Ensure that we don't have the assignment in the session, since
                    // we need to compare the existing one in the db to our edited assignment
                    session.evict(spreadsheet);

                    Spreadsheet sptFromDb = (Spreadsheet)session.load(Spreadsheet.class, spreadsheet.getId());
                    int numNameConflicts = ((Integer)session.createQuery(
                            "select count(spt) from Spreadsheet as spt where spt.name = ? and spt.gradebook = ? and spt.id != ?").
                            setString(0, spreadsheet.getName()).
                            setEntity(1, spreadsheet.getGradebook()).
                            setLong(2, spreadsheet.getId().longValue()).
                            uniqueResult()).intValue();
                    if(numNameConflicts > 0) {
                        throw new ConflictingAssignmentNameException("You can not save multiple spreadsheets in a gradebook with the same name");
                    }

                    session.evict(sptFromDb);
                    session.update(spreadsheet);

                    return null;
                }
            };
            try {
                getHibernateTemplate().execute(hc);
            } catch (HibernateOptimisticLockingFailureException holfe) {
                if(log.isInfoEnabled()) log.info("An optimistic locking failure occurred while attempting to update a spreadsheet");
                throw new StaleObjectModificationException(holfe);
            }
    }


    public Long createSpreadsheet(final Long gradebookId, final String name, final String creator, Date dateCreated, final String content) throws ConflictingSpreadsheetNameException,StaleObjectModificationException {

        HibernateCallback hc = new HibernateCallback() {
            public Object doInHibernate(Session session) throws HibernateException {
                Gradebook gb = (Gradebook)session.load(Gradebook.class, gradebookId);
                int numNameConflicts = ((Integer)session.createQuery(
                        "select count(spt) from Spreadsheet as spt where spt.name = ? and spt.gradebook = ? ").
                        setString(0, name).
                        setEntity(1, gb).
                        uniqueResult()).intValue();
                if(numNameConflicts > 0) {
                    throw new ConflictingSpreadsheetNameException("You can not save multiple spreadsheets in a gradebook with the same name");
                }

                Spreadsheet spt = new Spreadsheet();
                spt.setGradebook(gb);
                spt.setName(name);
                spt.setCreator(creator);
                spt.setDateCreated(new Date());
                spt.setContent(content);

                // Save the new assignment
                Long id = (Long)session.save(spt);
                return id;
            }
        };

        return (Long)getHibernateTemplate().execute(hc);

    }

    protected List getSpreadsheets(Long gradebookId, Session session) throws HibernateException {
        List spreadsheets = session.createQuery(
                "from Spreadsheet as spt where spt.gradebook.id=? ").
                setLong(0, gradebookId.longValue()).
                list();
        return spreadsheets;
    }

    public List getComments(final Assignment assignment, final Collection studentIds) {
    	if (studentIds.isEmpty()) {
    		return new ArrayList();
    	}
        return (List)getHibernateTemplate().execute(new HibernateCallback() {
            public Object doInHibernate(Session session) throws HibernateException {
            	List comments;
            	if (studentIds.size() <= MAX_NUMBER_OF_SQL_PARAMETERS_IN_LIST) {
            		Query q = session.createQuery(
            			"from Comment as c where c.gradableObject=:go and c.studentId in (:studentIds)");
                    q.setParameter("go", assignment);
                    q.setParameterList("studentIds", studentIds);
                    comments = q.list();
            	} else {
            		comments = new ArrayList();
            		Query q = session.createQuery("from Comment as c where c.gradableObject=:go");
            		q.setParameter("go", assignment);
            		List allComments = q.list();
            		for (Iterator iter = allComments.iterator(); iter.hasNext(); ) {
            			Comment comment = (Comment)iter.next();
            			if (studentIds.contains(comment.getStudentId())) {
            				comments.add(comment);
            			}
            		}
            	}
                return comments;
            }
        });
    }


    public List getStudentAssignmentComments(final String studentId, final Long gradebookId) {
        return (List)getHibernateTemplate().execute(new HibernateCallback() {
            public Object doInHibernate(Session session) throws HibernateException {
                List comments;
                comments = new ArrayList();
                Query q = session.createQuery("from Comment as c where c.studentId=:studentId and c.gradableObject.gradebook.id=:gradebookId");
                q.setParameter("studentId", studentId);
                q.setParameter("gradebookId",gradebookId);
                List allComments = q.list();
                for (Iterator iter = allComments.iterator(); iter.hasNext(); ) {
                    Comment comment = (Comment)iter.next();
                    comments.add(comment);
                }
                return comments;
            }
        });
    }
    
    public boolean validateCategoryWeighting(Long gradebookId)
    {
    	Gradebook gradebook = getGradebook(gradebookId);
    	if(gradebook.getCategory_type() != GradebookService.CATEGORY_TYPE_WEIGHTED_CATEGORY)
    		return true;
    	List cats = getCategories(gradebookId);
    	double weight = 0.0;
    	for(int i=0; i<cats.size(); i++)
    	{
    		Category cat = (Category) cats.get(i);
    		if(cat != null)
    		{
    			weight += cat.getWeight().doubleValue();
    		}
    	}
    	if(Math.rint(weight) == 1)
    		return true;
    	else
    		return false;
    }
    
    public Set updateAssignmentGradeRecords(Assignment assignment, Collection gradeRecords, int grade_type)
    {
    	if(grade_type == GradebookService.GRADE_TYPE_POINTS)
    		return updateAssignmentGradeRecords(assignment, gradeRecords);
    	else if(grade_type == GradebookService.GRADE_TYPE_PERCENTAGE)
    	{
    		Collection convertList = new ArrayList();
        for(Iterator iter = gradeRecords.iterator(); iter.hasNext();) 
        {
        	AssignmentGradeRecord agr = (AssignmentGradeRecord) iter.next();
        	Double doubleValue = calculateDoublePointForRecord(agr);
        	if(agr != null && doubleValue != null)
        	{
        		agr.setPointsEarned(doubleValue);
        		convertList.add(agr);
        	}
        	else if(agr != null)
        	{
        		agr.setPointsEarned(null);
        		convertList.add(agr);
        	}
        }
        return updateAssignmentGradeRecords(assignment, convertList);
    	}
    	else
    		return null;
    	//TODO letter grade type conversion
    }

    private Double calculateDoublePointForRecord(AssignmentGradeRecord gradeRecordFromCall)
    {
    	Assignment assign = getAssignment(gradeRecordFromCall.getAssignment().getId()); 
    	Gradebook gradebook = getGradebook(assign.getGradebook().getId());
    	if(gradeRecordFromCall.getPointsEarned() != null)
    	{
    		if(gradeRecordFromCall.getPointsEarned().doubleValue() < 0 || gradeRecordFromCall.getPointsEarned().doubleValue() > 1)
    		{
    			throw new IllegalArgumentException("point for record is greater than 1 or less than 1 for percentage points in GradebookManagerHibernateImpl.calculateDoublePointForRecord");
    		}
    		return new Double(assign.getPointsPossible().doubleValue() * gradeRecordFromCall.getPointsEarned().doubleValue());
    	}
    	else
    		return null;
    }
    
    public List getAssignmentGradeRecordsConverted(Assignment assignment, Collection studentUids)
    {
    	List assignRecordsFromDB = getAssignmentGradeRecords(assignment, studentUids);
    	Gradebook gradebook = getGradebook(assignment.getGradebook().getId());
    	if(gradebook.getGrade_type() == GradebookService.GRADE_TYPE_POINTS)
    		return assignRecordsFromDB;
    	else if(gradebook.getGrade_type() == GradebookService.GRADE_TYPE_PERCENTAGE)
    	{
    		return convertPointsToPercentage(assignment, gradebook, assignRecordsFromDB);
    	}
    	//TODO letter grading type?
    	return null;
    }

    private List convertPointsToPercentage(Assignment assignment, Gradebook gradebook, List assignRecordsFromDB)
    {
    	double pointPossible = assignment.getPointsPossible().doubleValue();
    	if(pointPossible > 0)
    	{
    		List percentageList = new ArrayList();
    		for(int i=0; i<assignRecordsFromDB.size(); i++)
    		{
    			AssignmentGradeRecord agr = (AssignmentGradeRecord) assignRecordsFromDB.get(i);
    			agr.setDateRecorded(agr.getDateRecorded());
    			agr.setGraderId(agr.getGraderId());
    			if(agr != null && agr.getPointsEarned() != null)
    			{
    				agr.setPointsEarned(new Double((agr.getPointsEarned().doubleValue())/pointPossible));
    				percentageList.add(agr);
    			}
    			else if(agr != null)
    			{
    				agr.setPointsEarned(null);
    				percentageList.add(agr);
    			}
    		}
    		return percentageList;
    	}
    	return null;
    }
    
    public List getCategoriesWithStats(Long gradebookId, String assignmentSort, boolean assignAscending, String categorySort, boolean categoryAscending) {
    	List categories = getCategories(gradebookId);
    	Set allStudentUids = getAllStudentUids(getGradebookUid(gradebookId));
    	List allAssignments;
    	if(assignmentSort != null)
    		allAssignments = getAssignmentsWithStats(gradebookId, assignmentSort, assignAscending);
    	else
    		allAssignments = getAssignmentsWithStats(gradebookId, Assignment.DEFAULT_SORT, assignAscending);
    	
    	List releasedAssignments = new ArrayList();
    	List gradeRecords = getAllAssignmentGradeRecords(gradebookId, allStudentUids);
    	Map cateMap = new HashMap();
    	for (Iterator iter = allAssignments.iterator(); iter.hasNext(); )
    	{
    		Assignment assign = (Assignment) iter.next();
    		if(assign != null)
    		{
    			if(assign.isReleased())
    			{
    				assign.calculateStatistics(gradeRecords);
    				releasedAssignments.add(assign);
    			}
    			if(assign.getCategory() != null && cateMap.get(assign.getCategory().getId()) == null)
    			{
    				List assignList = new ArrayList();
    				assignList.add(assign);
    				cateMap.put(assign.getCategory().getId(), assignList);
    			}
    			else
    			{
    				if(assign.getCategory() != null)
    				{
    					List assignList = (List) cateMap.get(assign.getCategory().getId());
    					assignList.add(assign);
    					cateMap.put(assign.getCategory().getId(),assignList);
    				}
    			}
    		}
    	}
    	
  		for (Iterator iter = categories.iterator(); iter.hasNext(); )
    	{
    		Category cate = (Category) iter.next();
    		if(cate != null && cateMap.get(cate.getId()) != null)
    		{
    			cate.calculateStatistics((List) cateMap.get(cate.getId()));
    			cate.setAssignmentList((List)cateMap.get(cate.getId()));
    		}
    	}
  		
  		if(categorySort != null)
  			sortCategories(categories, categorySort, categoryAscending);
  		else
  			sortCategories(categories, Category.SORT_BY_NAME, categoryAscending);
  			
      Set studentUids = getAllStudentUids(getGradebookUid(gradebookId));
      CourseGrade courseGrade = getCourseGrade(gradebookId);
      Map gradeRecordMap = new HashMap();
      addToGradeRecordMap(gradeRecordMap, gradeRecords);
      List<CourseGradeRecord> courseGradeRecords = getPointsEarnedCourseGradeRecords(courseGrade, studentUids, releasedAssignments, gradeRecordMap);
      courseGrade.calculateStatistics(courseGradeRecords, studentUids.size());

      categories.add(courseGrade);
  		
  		return categories;
    }

    private void sortCategories(List categories, String sortBy, boolean ascending) 
    {
    	Comparator comp;
    	if(Category.SORT_BY_NAME.equals(sortBy)) 
    	{
    		comp = Category.nameComparator;
    	}
    	else if(Category.SORT_BY_AVERAGE_SCORE.equals(sortBy))
    	{
    		comp = Category.averageScoreComparator;
    	}
    	else if(Category.SORT_BY_WEIGHT.equals(sortBy))
    	{
    		comp = Category.weightComparator;
    	}
    	else
    	{
    		comp = Category.nameComparator;
    	}
    	Collections.sort(categories, comp);
    	if(!ascending) 
    	{
    		Collections.reverse(categories);
    	}
    }

    public List getAssignmentsWithNoCategory(final Long gradebookId, String assignmentSort, boolean assignAscending)
    {
    	HibernateCallback hc = new HibernateCallback() {
    		public Object doInHibernate(Session session) throws HibernateException {
    			List assignments = session.createQuery(
    					"from Assignment as asn where asn.gradebook.id=? and asn.removed=false and asn.category is null").
    					setLong(0, gradebookId.longValue()).
    					list();
    			return assignments;
    		}
    	};
    	
    	List assignList = (List)getHibernateTemplate().execute(hc);
    	if(assignmentSort != null)
    		sortAssignments(assignList, assignmentSort, assignAscending);
    	else
    		sortAssignments(assignList, Assignment.DEFAULT_SORT, assignAscending);
    	
    	return assignList;
    }
}

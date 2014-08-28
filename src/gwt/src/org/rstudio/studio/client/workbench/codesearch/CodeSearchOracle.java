/*
 * CodeSearchOracle.java
 *
 * Copyright (C) 2009-12 by RStudio, Inc.
 *
 * Unless you have received this program directly from RStudio pursuant
 * to the terms of a commercial license agreement with RStudio, then
 * this program is licensed to you under the terms of version 3 of the
 * GNU Affero General Public License. This program is distributed WITHOUT
 * ANY EXPRESS OR IMPLIED WARRANTY, INCLUDING THOSE OF NON-INFRINGEMENT,
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE. Please refer to the
 * AGPL (http://www.gnu.org/licenses/agpl-3.0.txt) for more details.
 *
 */
package org.rstudio.studio.client.workbench.codesearch;

import java.util.ArrayList;
import java.util.Comparator;

import org.rstudio.core.client.CodeNavigationTarget;
import org.rstudio.core.client.Debug;
import org.rstudio.core.client.DuplicateHelper;
import org.rstudio.core.client.Invalidation;
import org.rstudio.core.client.StringUtil;
import org.rstudio.core.client.TimeBufferedCommand;
import org.rstudio.core.client.files.FileSystemItem;
import org.rstudio.core.client.regex.Match;
import org.rstudio.core.client.regex.Pattern;
import org.rstudio.studio.client.common.SimpleRequestCallback;
import org.rstudio.studio.client.workbench.WorkbenchContext;
import org.rstudio.studio.client.workbench.codesearch.model.CodeSearchResults;
import org.rstudio.studio.client.workbench.codesearch.model.RFileItem;
import org.rstudio.studio.client.workbench.codesearch.model.RSourceItem;
import org.rstudio.studio.client.workbench.codesearch.model.CodeSearchServerOperations;

import com.google.gwt.user.client.rpc.core.java.util.Collections;
import com.google.gwt.user.client.ui.SuggestOracle;
import com.google.inject.Inject;

public class CodeSearchOracle extends SuggestOracle
{
   @Inject
   public CodeSearchOracle(CodeSearchServerOperations server,
                           WorkbenchContext workbenchContext)
   {
      server_ = server;
      workbenchContext_ = workbenchContext;
   }
   
   private double score(String suggestion, String query)
   {
      int query_n = query.length();
      int suggestion_n = suggestion.length();
      double result = 0;
      for (int j = 0; j < query_n; j++)
      {
         // Weigh characters in the query that are matched earlier higher
         double queryWeight = Math.pow((query_n - j) / query_n, 2);
         
         // Weigh earlier matches higher than later matches
         int matchPos = suggestion.indexOf(query.charAt(j));
         double numerator = suggestion_n - matchPos + 1;
         double denominator = suggestion_n;
         double suggestionScore = numerator / denominator;
         
         result += queryWeight * suggestionScore;
      }
      
      // Debug.logToConsole("Score for suggestion '" + suggestion + "' against query '" + query + "': " + result);
      return result;
   }
   
   
   @Override
   public void requestSuggestions(final Request request, 
                                  final Callback callback)
   {    
      // invalidate any outstanding search
      searchInvalidation_.invalidate();
      
      // first see if we can serve the request from the cache
      for (int i=resultCache_.size() - 1; i >= 0; i--)
      {
         // get the previous result
         SearchResult res = resultCache_.get(i);
         
         // exact match of previous query
         if (request.getQuery().equals(res.getQuery()))
         {
            callback.onSuggestionsReady(request, 
                                        new Response(res.getSuggestions()));
            return;
         }
         
         // if this query is a further refinement of a non-overflowed 
         // previous query then satisfy it by filtering the previous results
         if (!res.getMoreAvailable() && 
             request.getQuery().startsWith(res.getQuery()))
         {
            Pattern pattern = null;
            final String queryLower = request.getQuery().toLowerCase();
            if (queryLower.indexOf('*') != -1)
               pattern = patternForTerm(queryLower);
            
            ArrayList<CodeSearchSuggestion> suggestions =
                                       new ArrayList<CodeSearchSuggestion>();
            for (int s=0; s<res.getSuggestions().size(); s++)
            {
               CodeSearchSuggestion sugg = res.getSuggestions().get(s);
               
               String name = sugg.getMatchedString().toLowerCase();
               if (pattern != null)
               {
                  Match match = pattern.match(name, 0);
                  if (match != null && match.getIndex() == 0)
                     suggestions.add(sugg);
               }
               else
               {
                  if (StringUtil.isSubsequence(name, queryLower))
                     suggestions.add(sugg);
               }
            }
            
            // process and cache suggestions. note that this adds an item to
            // the end of the resultCache_ (which we are currently iterating
            // over) no biggie because we are about to return from the loop
            suggestions = processSuggestions(request, suggestions, false);
            
            // return suggestions
            callback.onSuggestionsReady(request, new Response(suggestions));
            
            return;
         } 
      }
      
      // failed to short-circuit via the cache, hit the server
      codeSearch_.enqueRequest(request, callback); 
   }
     
   public CodeNavigationTarget navigationTargetFromSuggestion(Suggestion sugg)
   {
      return ((CodeSearchSuggestion)sugg).getNavigationTarget();
   }
   
   public void invalidateSearches()
   {
      searchInvalidation_.invalidate();
   }
   
   public boolean hasCachedResults()
   {
      return !resultCache_.isEmpty();
   }
   
   public void clear()
   {
      resultCache_.clear();
   }
   
   @Override
   public boolean isDisplayStringHTML()
   {
      return true;
   }
   
   private Pattern patternForTerm(String term)
   {
      // split the term on *
      StringBuilder regex = new StringBuilder();
      String[] components = term.split("\\*", -1);
      for (int i=0; i<components.length; i++)
      {
         if (i > 0)
            regex.append(".*");
         regex.append(Pattern.escape(components[i]));
      }    
      return Pattern.create(regex.toString());
   }
   
   private class CodeSearchCommand extends TimeBufferedCommand  
   {
      public CodeSearchCommand()
      {
         super(300);
      }
      
      public void enqueRequest(Request request, Callback callback)
      {
         request_ = request;
         callback_ = callback;
         invalidationToken_ = searchInvalidation_.getInvalidationToken();
         nudge();
      }

      @Override
      protected void performAction(boolean shouldSchedulePassive)
      {  
         // failed to short-circuit via the cache, hit the server
         server_.searchCode(
               request_.getQuery(),
               request_.getLimit(),
               new SimpleRequestCallback<CodeSearchResults>() {
            
            @Override
            public void onResponseReceived(CodeSearchResults response)
            {  
               ArrayList<CodeSearchSuggestion> suggestions = 
                                       new ArrayList<CodeSearchSuggestion>();
               
               // file results
               ArrayList<RFileItem> fileResults = 
                                    response.getRFileItems().toArrayList();
               for (int i = 0; i<fileResults.size(); i++) 
                  suggestions.add(new CodeSearchSuggestion(fileResults.get(i)));  
               
               
               // src results
               FileSystemItem context = workbenchContext_.getActiveProjectDir();
               ArrayList<RSourceItem> srcResults = 
                                    response.getRSourceItems().toArrayList();
               for (int i = 0; i<srcResults.size(); i++)
               {
                  suggestions.add(
                     new CodeSearchSuggestion(srcResults.get(i), context));    
               }
                  
               // process suggestions (disambiguate paths & cache)
              suggestions = processSuggestions(request_, 
                                               suggestions,
                                               response.getMoreAvailable());
              
              // sort the suggestions -- we want suggestions for which
              // the query matches the start to come first
              final String queryLower = request_.getQuery().toLowerCase();
              java.util.Collections.sort(suggestions,
                    new Comparator<CodeSearchSuggestion>() {
                 
                 @Override
                 public int compare(CodeSearchSuggestion lhs,
                       CodeSearchSuggestion rhs)
                 {
                    double lhsScore = score(
                          lhs.getMatchedString().toLowerCase(),
                          queryLower);
                    
                    double rhsScore = score(
                          rhs.getMatchedString().toLowerCase(),
                          queryLower);
                    
                    if (lhsScore == rhsScore)
                    {
                       return 0;
                    }
                    else
                    {
                       return lhsScore > rhsScore ? -1 : 1;
                    }
                 }
                 
              });
               
               // return suggestions
               if (!invalidationToken_.isInvalid())
               {
                  callback_.onSuggestionsReady(request_, 
                                               new Response(suggestions));
               }
            }
         });
         
      }
      
      private Request request_;
      private Callback callback_;
      private Invalidation.Token invalidationToken_;
   };
   
   
   private ArrayList<CodeSearchSuggestion> processSuggestions(
                                   Request request, 
                                   ArrayList<CodeSearchSuggestion> suggestions,
                                   boolean moreAvailable)
   {
      // get file paths for file targets (which are always at the beginning)
      ArrayList<String> filePaths = new ArrayList<String>();
      for(CodeSearchSuggestion suggestion : suggestions)
      {
         if (!suggestion.isFileTarget())
            break;
         
         filePaths.add(suggestion.getNavigationTarget().getFile());
      }
      
      // disambiguate them
      ArrayList<String> displayLabels = DuplicateHelper.getPathLabels(filePaths,
                                                                      true);
      ArrayList<CodeSearchSuggestion> newSuggestions =
                            new ArrayList<CodeSearchSuggestion>(suggestions);
      for (int i=0; i<displayLabels.size(); i++)
         newSuggestions.get(i).setFileDisplayString(filePaths.get(i),
                                                    displayLabels.get(i));
      
      // cache the suggestions (up to 15 active result sets cached)
      // NOTE: the cache is cleared on gain focus, lost focus, and 
      // the search term reverting back to empty)
      if (resultCache_.size() > 15)
         resultCache_.remove(0);
      resultCache_.add(new SearchResult(request.getQuery(), 
                                        newSuggestions, 
                                        moreAvailable));
      
      return newSuggestions;
   }
   
   private final Invalidation searchInvalidation_ = new Invalidation();
   
   private final CodeSearchServerOperations server_ ;
   private final WorkbenchContext workbenchContext_;
   private final CodeSearchCommand codeSearch_ = new CodeSearchCommand();
   
   private final ArrayList<SearchResult> resultCache_ = 
                                             new ArrayList<SearchResult>();
   
   private class SearchResult
   {
      public SearchResult(String query, 
                          ArrayList<CodeSearchSuggestion> suggestions,
                          boolean moreAvailable)
      {
         query_ = query;
         suggestions_ = suggestions;
         moveAvailable_ = moreAvailable;
      }
      
      public String getQuery()
      {
         return query_;
      }
      
      public ArrayList<CodeSearchSuggestion> getSuggestions()
      {
         return suggestions_;
      }
      
      public boolean getMoreAvailable()
      {
         return moveAvailable_;
      }
      
      private final String query_;
      private final ArrayList<CodeSearchSuggestion> suggestions_;
      private final boolean moveAvailable_;
   }
   
}


//scrape

async function performWebScraping(link) {
    const response = await fetch('http://localhost:3000/scrape', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify({ link }),
        
    });

    if (!response.ok) {
        throw new Error(`HTTP error! Status: ${response.status}`);
    }
    
    const { ocrText } = await response.json();
    
    const scrapetext = ocrText;

    // Now you can use the OCR text as needed in your client-side code
    const systemTxt = document.getElementById('systemtxt');
    systemTxt.value += `first Text from scraping: ${ocrText}`;
    console.log('link: ' + link);
}


// send user input
// Trigger search keyword

async function fetchHtmlContent(link) {
    try {
        const response = await fetch(link);

        if (!response.ok) {
            throw new Error(`HTTP error! Status: ${response.status}`);
        }

        const htmlContent = await response.text();
        return htmlContent;
    } catch (error) {
        console.error('Error fetching HTML content:', error);
        return null;
    }
}

const SEARCH_KEYWORD = 'Search';
const SEESCREEN_KEYWORD = 'seescren'

async function sendMessage() {
    
  
    const userQuery = document.getElementById('query').value;
    const outputElement = document.getElementById('output');

    try {
        // Check if the "seescreen" keyword is present in the user's query----
        if (userQuery.toLowerCase().includes(SEESCREEN_KEYWORD.toLowerCase())) {
            console.log(`Seescreen triggered with query: ${userQuery}`);

            // Capture screenshot and perform OCR
            const seescreenResponse = await fetch('http://localhost:3000/seescreen', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify({ query: userQuery }),
            });

            if (!seescreenResponse.ok) {
                throw new Error(`HTTP error! Status: ${seescreenResponse.status}`);
            }

            const seescreenData = await seescreenResponse.json();
            const ocrText = seescreenData.ocrText;
            const systemTxt = document.getElementById('systemtxt');
            systemTxt.value = `Extracted text from screenshot: ${ocrText}\n\n`;

            // Send the user query along with the extracted text to GPT
            const gptResponse = await fetch('http://localhost:3000/ask', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify({
                    query: userQuery,
                    systemtxt: systemTxt.value,
                }),
            });

            if (!gptResponse.ok) {
                throw new Error(`HTTP error! Status: ${gptResponse.status}`);
            }

            const gptData = await gptResponse.json();
            const botResponse = gptData.botResponse;

            // Update output element with bot response
            const line = 'REPLY:';
            outputElement.value = `${outputElement.value}\n\n${line}\n\n${botResponse}`;
            outputElement.scrollTop = outputElement.scrollHeight;
        }
        //----    
        // Check if the search keyword is present in the user's query
        if (userQuery.toLowerCase().includes(SEARCH_KEYWORD.toLowerCase())) {
            // Remove the keyword to get the actual query for searching
            const searchQuery = userQuery.toLowerCase().replace(SEARCH_KEYWORD.toLowerCase(), '').trim();

            // Implement your logic to trigger a search with searchQuery
            console.log(`Search triggered with query: ${searchQuery}`);
            
            // Use fetch or any other method to perform the search 
            const response = await fetch(`https://www.googleapis.com/customsearch/v1?q=${encodeURIComponent(searchQuery)}&key=REMOVED_API_KEY&cx=14859d15223854b96&num=1`);
            if (!response.ok) {
                throw new Error(`HTTP error! Status: ${response.status}`);
            }
            const askresponse = await fetch ('http://localhost:3000/ask', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify({
                    query: userQuery,
                    systemtxt: document.getElementById('systemtxt').value,
                    searchQuery: searchQuery,
                    
                }), 
            });


            const data = await response.json();
            // Process the search results here
            const searchResults = data.items;
          
            let systemTxtValue = `You use the following info to answer the question, find information or make a resume if you can't answere the question in a concrete way. `;
         
            const scrapePromises = searchResults.forEach (result => {
            const title = result.title;
            const snippet = result.snippet;
            const link = result.link;
            
            const linkHeader = document.getElementById('linkheader');
            linkHeader.innerText = link;
            linkHeader.href = link;
            console.log(linkHeader.value);

            async function scr () {
            const linkToScrape = link;
            await performWebScraping(linkToScrape);
            };

            scr ();
            //console.log(link);

             // Append each result to the accumulated string
             systemTxtValue += `Title: ${title}\nSnippet: ${snippet}\nLink: ${link} \n\n`;
             console.log(`Title: ${title}`);
             console.log(`Snippet: ${snippet}`);
             console.log(`Link: ${link}`);
             });
             
             // Set the accumulated value to systemtxt.value
             const systemTxt = document.getElementById('systemtxt');
             systemTxt.value = systemTxtValue;

                
              // Send a request to the server to scrape the link
              const scrapeResponse = await fetch('http://localhost:3000/scrape', {
                 method: 'POST',
                 headers: {
                   'Content-Type': 'application/json',
                  },
                  body: JSON.stringify({
                  link: searchResults[0].link, // Assuming you want to scrape the first link
                 }),
                });

             if (scrapeResponse.ok) {
                 const scrapeResult = await scrapeResponse.json();
                 console.log('Scraped Link:', scrapeResult.link);
                 console.log('OCR Text:', scrapeResult.ocrText);
                 
                 // Include the OCR text in the system message
                systemTxt.value += `\n\n` + `last OCR Text from scrape: ${scrapeResult.ocrText}`;
               }
               
               if (Array.isArray(scrapePromises) && scrapePromises.length > 0) {
                // Wait for all the asynchronous operations to complete
                await Promise.all(scrapePromises);
               }

                document.getElementById('query').value = ' make a summary from the info you got, or respond about this question (Important: include website link at the end of your answer, under a line break): ' + searchQuery + '?';
                console.log('empty input field');
                document.getElementById('button1').click();
                document.getElementById('query').value = '';
                
                const systemtxt = document.getElementById('systemtxt');
                systemtxt.value = "";
                console.log('ny system text',systemtxt.value)

        } else {
            // Proceed with the regular conversation flow
            const response = await fetch('http://localhost:3000/ask', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify({ 
                    query: userQuery,
                    systemtxt: document.getElementById('systemtxt').value, }), 
            });

            if (!response.ok) {
                throw new Error(`HTTP error! Status: ${response.status}`);
            }

            const result = await response.json();
            const botResponse = result.botResponse; // Extract the 'botResponse' property
            const line = 'REPLY:';
            outputElement.value = outputElement.value + '\n\n' +  line  + '\n\n' + botResponse;
            outputElement.scrollTop = outputElement.scrollHeight;
            
            

            
            
        }
    } catch (error) {
        console.error('Error:', error);
    }
}





// Function to populate voice options -------------------------------------
function populateVoices() {
    const voiceSelect = document.getElementById('language');
    voices = window.speechSynthesis.getVoices();

    // Clear existing options
    voiceSelect.innerHTML = '';

    if (voices.length === 0) {
        // Voices are not available yet, wait for onvoiceschanged event
        window.speechSynthesis.onvoiceschanged = function () {
            populateVoices(); // Recursive call after voices are available
        };
    } else {
        // Voices are available, populate the options
        voices.forEach((voice, i) => {
            voiceSelect.options[i] = new Option(voice.name, i);
        });
    }
}

// Call the populateVoices function to set up voice options initially
populateVoices();

// Function to populate speed options
function populateSpeeds() {
    const speedSelect = document.getElementById('speed');
    const speeds = ['1', '1.5', '0.5']; // Adjust the speed values as needed

    // Clear existing options
    speedSelect.innerHTML = '';

    speeds.forEach((speed, i) => {
        speedSelect.options[i] = new Option(speed === '1' ? '1' : speed, speed);
    });
}

// Call the populateSpeeds function to set up speed options initially
populateSpeeds();

// Function to speak bot response
function speakBotResponse() {
    const outputElement = document.getElementById('output');
    const allResponses = outputElement.value.split('REPLY:'); // Split by "REPLY:"

    // Get the selected voice
    const voiceSelect = document.getElementById('language');
    const selectedVoiceIndex = voiceSelect.selectedIndex;
    const selectedVoice = window.speechSynthesis.getVoices()[selectedVoiceIndex];

    // Get the selected speed
    const selectedSpeed = document.getElementById('speed').value;

    // Clear the utterance list before speaking
    window.speechSynthesis.cancel();

    // Speak only the content after the last "REPLY:"
    const lastResponse = allResponses.pop().trim(); // Get the last response and remove leading/trailing whitespace

    let speech = new SpeechSynthesisUtterance();
    speech.text = lastResponse;
    speech.voice = selectedVoice;
    speech.rate = parseFloat(selectedSpeed);

    // Start speaking
    window.speechSynthesis.speak(speech);
}


// Add an event listener to the new button with id="button2" for speech
document.getElementById('button2').addEventListener('click', speakBotResponse);


// Add event listeners to the 'language' and 'speed' selects to prevent speech on change
document.getElementById('language').addEventListener('change', function (event) {
    event.stopPropagation();
});

document.getElementById('speed').addEventListener('change', function (event) {
    event.stopPropagation();
});


//-------------------------SEARCH API 


document.getElementById('button3').addEventListener('click', async () => {
    await performSearch();
});

// Extracted the search logic into a function (button 3!!!!!!!!!!!!!!!!!!)
async function performSearch() {

    const searchInput = userQuery.toLowerCase().replace(SEARCH_KEYWORD.toLowerCase(), '').trim();


    try {
        const response = await fetch(`https://www.googleapis.com/customsearch/v1?q=${encodeURIComponent(searchInput)}&key=REMOVED_API_KEY&cx=14859d15223854b96&num=2`);
        if (!response.ok) {
            throw new Error(`HTTP error! Status: ${response.status}`);
        }

        const data = await response.json();
        const searchResults = data.items;
        searchResults.forEach(result => {
            const title = result.title;
            const snippet = result.snippet;
            const link = result.link;

            console.log(`Title: ${title}`);
            console.log(`Snippet: ${snippet}`);
            console.log(`Link: ${link}`);
        });
    } catch (error) {
        console.error('Error:', error);
    }
};


//---------- Search keyword trigger--------------------------

async function getmessages() {

    


    try {
        const response = await fetch(``);
        if (!response.ok) {
            throw new Error(`HTTP error! Status: ${response.status}`);
        }

        const data = await response.json();
        const searchResults = data.items;
        searchResults.forEach(result => {
            const sender = result.sender;
            const snippet = result.snippet;
            const link = result.link;

        });
    } catch (error) {
        console.error('Error:', error);
    }
};

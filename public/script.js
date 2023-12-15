


// send user input
// Trigger search keyword



const SEARCH_KEYWORD = 'Search';

async function sendMessage() {
    const userQuery = document.getElementById('query').value;
    const outputElement = document.getElementById('output');

    try {
        // Check if the search keyword is present in the user's query
        if (userQuery.toLowerCase().includes(SEARCH_KEYWORD.toLowerCase())) {
            // Remove the keyword to get the actual query for searching
            const searchQuery = userQuery.toLowerCase().replace(SEARCH_KEYWORD.toLowerCase(), '').trim();

            // Implement your logic to trigger a search with searchQuery
            console.log(`Search triggered with query: ${searchQuery}`);
            
            // Use fetch or any other method to perform the search 
            const response = await fetch(`https://www.googleapis.com/customsearch/v1?q=${encodeURIComponent(searchQuery)}&key=REMOVED_API_KEY&cx=14859d15223854b96&num=3`);
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
          
            let systemTxtValue = ` Answer :`;

            searchResults.forEach(result => {
            const title = result.title;
            const snippet = result.snippet;
            const link = result.link;

             // Append each result to the accumulated string
             systemTxtValue += `Title: ${title}\nSnippet: ${snippet}\nLink: ${link}\n\n`;
             console.log(`Title: ${title}`);
             console.log(`Snippet: ${snippet}`);
             console.log(`Link: ${link}`);
             });

             // Set the accumulated value to systemtxt.value
             const systemTxt = document.getElementById('systemtxt');
             systemTxt.value = systemTxtValue;

                // Process or display the information as needed
                
                
                //document.getElementById('usertxt').value = `${searchQuery}?`;
                //document.getElementById('systemtxt').value = "";
                
            
                 
                
                //console.log(document.getElementById('systemtxt').value);
                
                
                //document.getElementById('button4').click();
                //if (userQuery.toLowerCase().includes(SEARCH_KEYWORD.toLowerCase())) {
                //    userQuery.toLowerCase().replace(SEARCH_KEYWORD.toLowerCase(), '').trim();
                //    console.log('fjerna search');
                //};

               

                document.getElementById('query').value = 'continue';
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
    const speeds = ['0.3', '1', '2']; // Adjust the speed values as needed

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
    const botResponse = outputElement.value;

    // Find the last occurrence of "-" in the text
    const lastHyphenIndex = botResponse.lastIndexOf('REPLY:');

    // Extract the text after the last "-"
    const textToSpeak = lastHyphenIndex !== -1 ? botResponse.substring(lastHyphenIndex + 1) : botResponse;

    // Get the selected voice
    const voiceSelect = document.getElementById('language');
    const selectedVoiceIndex = voiceSelect.selectedIndex;
    const selectedVoice = window.speechSynthesis.getVoices()[selectedVoiceIndex];

    // Get the selected speed
    const selectedSpeed = document.getElementById('speed').value;

    let speech = new SpeechSynthesisUtterance();
    speech.text = textToSpeak;
    speech.voice = selectedVoice;
    speech.rate = parseFloat(selectedSpeed);

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


